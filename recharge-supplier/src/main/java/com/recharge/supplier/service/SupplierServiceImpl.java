package com.recharge.supplier.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.recharge.common.cache.CacheService;
import com.recharge.common.entity.*;
import com.recharge.common.enums.OrderStatusEnum;
import com.recharge.common.enums.TaskTypeEnum;
import com.recharge.common.recharge.CheckStatusRequest;
import com.recharge.common.recharge.RechargeRequest;
import com.recharge.common.recharge.RechargeResponse;
import com.recharge.common.utils.ProtostuffUtil;
import com.recharge.feign.agent.ScheduleServiceAgent;
import com.recharge.supplier.config.SupplierConfig;
import com.recharge.trade.entity.OrderTrade;
import com.recharge.trade.mapper.OrderTradeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Calendar;
import java.util.Map;
import java.util.Set;

/**
 * @author yuelimin
 * @version 1.0.0
 * @since 1.8
 */
@Slf4j
@Service
public class SupplierServiceImpl implements SupplierService {
    @Autowired
    private SupplierConfig supplierConfig;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private SupplierTask supplierTask;
    @Autowired
    private OrderTradeMapper orderTradeMapper;
    @Autowired
    private CacheService cacheService;
    @Autowired
    private ScheduleServiceAgent scheduleServiceAgent;

    @Override
    public void addCheckStatusTask(CheckStatusRequest checkStatusRequest) {
        Task task = new Task();
        TaskTypeEnum taskTypeEnum = TaskTypeEnum.STATE_CHECK;
        task.setTaskType(taskTypeEnum.getTaskType());
        task.setPriority(taskTypeEnum.getPriority());
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, supplierConfig.getStateCheckTime());
        task.setExecuteTime(calendar.getTimeInMillis());

        task.setParameters(ProtostuffUtil.serialize(checkStatusRequest));
        // ????????????????????????
        ResponseMessage result = scheduleServiceAgent.push(task);
        // ??????????????????????????????????????????????????????????????????,
        // ????????????????????????id,
        // ????????????????????????????????????????????????
        // ??????????????????????????????id?????????????????????
        if (result.getCode() == StatusCode.OK) {
            cacheService.hPut(Constants.ORDER_CHECKED, checkStatusRequest.getOrderNo(), String.valueOf(result.getData()));
        }
    }

    @Override
    @Scheduled(fixedRate = 1000)
    public void checkStatus() {
        // ????????????????????????-??????????????????
        TaskTypeEnum statecheck = TaskTypeEnum.STATE_CHECK;
        ResponseMessage poll = scheduleServiceAgent.poll(statecheck.getTaskType(), statecheck.getPriority());
        if (poll.isFlag()) {
            if (poll.getData() != null) {
                String taskStr = JSON.toJSONString(poll.getData());
                Task task = JSON.parseObject(taskStr, new TypeReference<Task>() {
                });
                CheckStatusRequest statusRequest = ProtostuffUtil.deserialize(task.getParameters(), CheckStatusRequest.class);
                log.info("???????????????????????????????????????{}", statusRequest);
                // ??????????????????????????????????????????
                checkStatus(statusRequest);
            }
        }
    }

    @Override
    public void checkStatus(CheckStatusRequest checkStatusRequest) {
        // ??????????????????????????????
        String checkStatusApi = supplierConfig.getCheckStateApis().get(checkStatusRequest.getSupplier());
        // ???????????????
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // ??????????????????-???????????????????????????????????????????????????, ?????????????????????????????????????????????????????????????????????,
        // ??????????????????????????????????????????????????????
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("outorderNo", checkStatusRequest.getOrderNo());
        map.add("tradeNo", checkStatusRequest.getTradeNo());

        HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(map, headers);
        ResponseEntity<String> responseEntity = restTemplate.postForEntity(checkStatusApi, httpEntity, String.class);
        Result<RechargeResponse> result = JSON.parseObject(responseEntity.getBody(), new TypeReference<Result<RechargeResponse>>() {
        });

        assert result != null;
        if (result.getCode() == StatusCode.OK) {
            log.info("??????????????????, ????????????{}", checkStatusRequest);
            updateTrade(checkStatusRequest.getOrderNo(), result.getData().getStatus());
        } else {
            // ????????????
            log.info("??????????????????, ????????????{}", checkStatusRequest);
            updateTrade(checkStatusRequest.getOrderNo(), OrderStatusEnum.FAIL.getCode());
        }
    }

    @Override
    public void recharge(RechargeRequest rechargeRequest) {
        // ???????????????????????? ???????????????????????????, ?????????????????????, ????????????-????????????????????????????????????
        if (rechargeRequest.getRepeat() > supplierConfig.getMaxrepeat()) {
            updateTrade(rechargeRequest.getOrderNo(), OrderStatusEnum.FAIL.getCode());
            return;
        }

        String checkSupply = checkSupply(rechargeRequest.getSupply());
        if (checkSupply != null) {
            rechargeRequest.setSupply(checkSupply);
        } else {
            updateTrade(rechargeRequest.getOrderNo(), OrderStatusEnum.FAIL.getCode());
            return;
        }

        Result<RechargeResponse> result = null;
        try {
            result = doDispatchSupplier(rechargeRequest);
        } catch (Exception e) {
            log.error("recharge exception, {}", e.getMessage());
            // ??????????????????????????????
            rechargeRequest.setErrorCode(StatusCode.REMOTE_ERROR);
            supplierTask.addRetryTask(rechargeRequest);
            return;
        }

        if (result != null) {
            // ????????????????????????
            if (result.getCode() == StatusCode.OK) {
                log.info("????????????, ????????????????????????.");
                // ????????????????????????????????????????????????????????????-??????????????????????????????????????????
                // ???????????????????????????
                updateTrade(rechargeRequest.getOrderNo(), OrderStatusEnum.UNAFFIRM.getCode());
                log.info("????????????, ????????????????????????, 1???????????????????????????");
                addCheckStatusTask(new CheckStatusRequest(rechargeRequest.getSupply(), result.getData().getOrderNo(), result.getData().getTradeNo()));
                return;
            } else {
                // ?????????????????????
                // ??????????????????, ?????????????????????
                if (result.getCode() == StatusCode.BALANCE_NOT_ENOUGH) {
                    // ???????????????????????????????????????reids ???????????????
                    cacheService.sAdd(Constants.EXCLUDE_SUPPLIER, rechargeRequest.getSupply());
                    String nextSupply = nextSupply();
                    System.out.println("???????????????????????????:" + nextSupply);
                    if (nextSupply != null) {
                        rechargeRequest.setSupply(nextSupply);
                        rechargeRequest.setRepeat(0);
                        rechargeRequest.setErrorCode(StatusCode.BALANCE_NOT_ENOUGH);
                    } else {
                        // ??????????????????
                        updateTrade(rechargeRequest.getOrderNo(), OrderStatusEnum.FAIL.getCode());
                        return;
                    }
                } else if (result.getCode() == StatusCode.ORDER_REQ_FAILED) {
                    // ?????????????????????-??????????????????
                    rechargeRequest.setErrorCode(StatusCode.ORDER_REQ_FAILED);
                }
                supplierTask.addRetryTask(rechargeRequest);
            }
        }
    }

    private String checkSupply(String supply) {
        Set<String> excludes = cacheService.setMembers(Constants.EXCLUDE_SUPPLIER);
        if (excludes.contains(supply)) {
            return nextSupply();
        } else {
            return supply;
        }
    }

    private String nextSupply() {
        Set<String> excludes = cacheService.setMembers(Constants.EXCLUDE_SUPPLIER);
        Map<String, String> allApis = supplierConfig.getApis();
        for (String supply : allApis.keySet()) {
            if (!excludes.contains(supply)) {
                return supply;
            }
        }
        return null;
    }

    private void updateTrade(String orderNo, int orderStatus) {
        // ??????????????????
        QueryWrapper<OrderTrade> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("order_no", orderNo);
        OrderTrade orderTrade = orderTradeMapper.selectOne(queryWrapper);
        if (orderTrade != null) {
            orderTrade.setOrderStatus(orderStatus);
            orderTradeMapper.updateById(orderTrade);
        }
    }

    /**
     * ??????????????????
     *
     * @param rechargeRequest
     */
    private Result<RechargeResponse> doDispatchSupplier(RechargeRequest rechargeRequest) {
        // ??????????????????????????????:
        String url = supplierConfig.getApis().get(rechargeRequest.getSupply());
        rechargeRequest.setRechargeUrl(url);

        // ??????????????????????????????????????????????????????????????????-?????????api?????????????????????????????????????????????????????????
        if (Constants.JU_HE_API.equals(rechargeRequest.getSupply())) {
            // ????????????
            return doPostJuhe(rechargeRequest);
        } else if (Constants.JI_SU_API.equals(rechargeRequest.getSupply())) {
            // ????????????
            return doPostJisu(rechargeRequest);
        }

        return null;
    }

    private Result<RechargeResponse> doPostJuhe(RechargeRequest rechargeRequest) {
        // ????????????????????????json???????????????
        // ????????????????????????
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // ??????????????????
        HttpEntity httpEntity = new HttpEntity(JSON.toJSONString(rechargeRequest), headers);
        // ????????????
        ResponseEntity<String> responseEntity = restTemplate.postForEntity(rechargeRequest.getRechargeUrl(), httpEntity, String.class);

        Result<RechargeResponse> result = JSON.parseObject(responseEntity.getBody(), new TypeReference<Result<RechargeResponse>>() {
        });

        log.info("????????????????????????????????????-{}", result);

        return result;
    }

    private Result<RechargeResponse> doPostJisu(RechargeRequest rechargeRequest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // ??????????????????
        MultiValueMap<String, String> map = new LinkedMultiValueMap<String, String>();
        map.add("mobile", rechargeRequest.getMobile());
        map.add("amount", rechargeRequest.getPamt() + "");
        map.add("outorderNo", rechargeRequest.getOrderNo());
        map.add("repeat", "" + rechargeRequest.getRepeat());

        // ??????????????????
        // map.add("req_status", "" + StatusCode.ERROR);
        // ??????????????????
        map.add("req_status", "" + StatusCode.OK);
        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<MultiValueMap<String, String>>(map, headers);
        ResponseEntity<String> responseEntity = restTemplate.postForEntity(rechargeRequest.getRechargeUrl(), requestEntity, String.class);
        // ?????????????????????
        Result<RechargeResponse> result = JSON.parseObject(responseEntity.getBody(), new TypeReference<Result<RechargeResponse>>() {
        });

        log.info("????????????????????????????????????-{}", result);

        return result;
    }
}
