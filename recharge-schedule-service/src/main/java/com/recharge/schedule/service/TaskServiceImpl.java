package com.recharge.schedule.service;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.recharge.common.cache.CacheService;
import com.recharge.common.entity.Constants;
import com.recharge.common.entity.Task;
import com.recharge.common.exception.ScheduleSystemException;
import com.recharge.common.exception.TaskNotExistException;
import com.recharge.schedule.config.SystemParamsConfiguration;
import com.recharge.schedule.entity.TaskInfo;
import com.recharge.schedule.entity.TaskInfoLogs;
import com.recharge.schedule.mapper.TaskInfoLogsMapper;
import com.recharge.schedule.mapper.TaskInfoMapper;
import com.recharge.schedule.zookeeper.SelectMaster;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @author yuelimin
 * @version 1.0.0
 * @since 1.8
 */
@Slf4j
@Service
public class TaskServiceImpl implements TaskService {
    @Autowired
    private TaskInfoMapper taskMapper;
    @Autowired
    private TaskInfoLogsMapper taskLogMapper;
    @Autowired
    private CacheService cacheService;
    @Autowired
    private SystemParamsConfiguration systemParamsConfiguration;

    @Autowired
    private ThreadPoolTaskScheduler threadPoolTaskScheduler;
    @Autowired
    private SelectMaster selectMaster;

    @Resource(name = "visiableThreadPool")
    private ThreadPoolTaskExecutor threadPool;

    private long nextScheduleTime;

    @PostConstruct
    private void syncData() {
        // ?????????????????????
        selectMaster.selectMaster(Constants.SCHEDULE_LEADER_PATH);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        threadPoolTaskScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                // ????????????????????????????????????
                if (selectMaster.checkMaster(Constants.SCHEDULE_LEADER_PATH)) {
                    log.info("schedule-service???????????????????????????");
                    reloadData();
                } else {
                    log.info("schedule-service???????????????");
                }
            }
        }, TimeUnit.MINUTES.toMillis(systemParamsConfiguration.getReload()));
    }

    private void reloadData() {
        log.info("reload data");

        // ??????????????????????????????
        clearCache();
        QueryWrapper<TaskInfo> wrapper = new QueryWrapper<>();
        wrapper.select("task_type", "priority");
        wrapper.groupBy("task_type", "priority");
        List<Map<String, Object>> maps = taskMapper.selectMaps(wrapper);

        long start = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(maps.size());

        // ????????????5????????????
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, systemParamsConfiguration.getReload());
        nextScheduleTime = calendar.getTimeInMillis();
        // ?????????????????????
        cacheService.set(Constants.NEXT_SCHEDULE_TIME, nextScheduleTime + "");

        for (Map<String, Object> map : maps) {
            threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    long start = System.currentTimeMillis();

                    String taskType = String.valueOf(map.get("task_type"));
                    String priority = String.valueOf(map.get("priority"));

                    List<TaskInfo> allTaskInfo = taskMapper.queryFutureTime(Integer.parseInt(taskType), Integer.parseInt(priority), calendar.getTime());
                    for (TaskInfo taskInfo : allTaskInfo) {
                        Task task = new Task();
                        // ????????????
                        BeanUtils.copyProperties(taskInfo, task);
                        task.setExecuteTime(taskInfo.getExecuteTime().getTime());
                        // ????????????
                        addTaskToCache(task);
                    }

                    latch.countDown();
                    // ?????????????????????????????????
                    log.info("??????-{}, ?????????-{}, ??????????????????-{}", Thread.currentThread().getName(), latch.getCount(), System.currentTimeMillis() - start);
                }
            });
        }

        // ??????????????????, ???????????????????????????
        try {
            latch.await(1, TimeUnit.MINUTES);
            log.info("??????????????????, ?????????:" + (System.currentTimeMillis() - start) + "??????");
        } catch (InterruptedException e) {
            log.error("??????????????????, ????????????{}", e.getMessage());
        }
    }

    @Override
    public void refresh() {
        log.info("refresh time {}", System.currentTimeMillis() / 1000);

        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                // ???????????????????????????????????????key
                Set<String> keys = cacheService.scan(Constants.FUTURE + "*");// future_*
                for (String key : keys) {
                    // key= future_1001_3
                    String topicKey = Constants.TOPIC + key.split(Constants.FUTURE)[1];
                    // ????????????key?????????????????????
                    Set<String> values = cacheService.zRangeByScore(key, 0, System.currentTimeMillis());
                    // ??????????????????????????????????????????????????????????????????????????????
                    if (!values.isEmpty()) {
                        cacheService.refreshWithPipeline(key, topicKey, values);
                        log.info("flash " + key + "to " + topicKey + " successfully.");
                    }
                }
            }
        });
    }

    private void clearCache() {
        // ?????????????????????????????????key
        // future_*
        Set<String> futureKeys = cacheService.scan(Constants.FUTURE + "*");
        cacheService.delete(futureKeys);

        // ??????????????????????????????key
        // topic_*
        Set<String> topicKeys = cacheService.scan(Constants.TOPIC + "*");
        cacheService.delete(topicKeys);
    }

    @Override
    public long size(int type, int priority) {
        // ????????????=????????????????????????????????????+???????????????????????????
        String key = type + "_" + priority;
        Set<String> zRangeAll = cacheService.zRangeAll(Constants.FUTURE + key);
        Long len = cacheService.lLen(Constants.TOPIC + key);
        return zRangeAll.size() + len;
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    public Task poll(int type, int priority) throws TaskNotExistException {
        Future<Task> future = threadPool.submit(new Callable<Task>() {
            @Override
            public Task call() throws Exception {
                Task task = null;
                // ?????????????????????????????????
                String key = type + "_" + priority;
                String taskJson = cacheService.lLeftPop(Constants.TOPIC + key);
                if (!StringUtils.isEmpty(taskJson)) {
                    task = JSON.parseObject(taskJson, Task.class);

                    // ?????????????????????
                    updateDb(task.getTaskId(), Constants.EXECUTED);
                }
                return task;
            }
        });

        // ????????????????????????
        Task task = null;
        try {
            task = future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("poll task exception");
            throw new TaskNotExistException(e);
        }

        return task;
    }

    @Override
    @Transactional(rollbackFor = {Exception.class})
    public long addTask(Task task) throws ScheduleSystemException {
        /**
         * addTaskToDb()
         * ???????????????????????????addTaskToCache();
         */
        Future<Long> future = threadPool.submit(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                boolean success = addTaskToDb(task);
                if (success) {
                    addTaskToCache(task);
                }
                return task.getTaskId();
            }
        });
        long taskId = -1;
        // ????????????
        try {
            taskId = future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("add task exception");
            throw new ScheduleSystemException(e);
        }
        return taskId;
    }

    private boolean addTaskToDb(Task task) throws ScheduleSystemException {
        boolean flag = false;

        try {
            // ?????????????????????
            TaskInfo taskInfo = new TaskInfo();
            taskInfo.setTaskType(task.getTaskType());
            taskInfo.setParameters(task.getParameters());
            taskInfo.setPriority(task.getPriority());
            taskInfo.setExecuteTime(new Date(task.getExecuteTime()));
            taskMapper.insert(taskInfo);

            // ??????????????????id
            task.setTaskId(taskInfo.getTaskId());

            // ??????????????????
            TaskInfoLogs taskLog = new TaskInfoLogs();
            taskLog.setExecuteTime(taskInfo.getExecuteTime());
            taskLog.setPriority(taskInfo.getPriority());
            taskLog.setParameters(taskInfo.getParameters());
            taskLog.setTaskType(taskInfo.getTaskType());
            taskLog.setTaskId(taskInfo.getTaskId());
            taskLog.setVersion(1);
            taskLog.setStatus(Constants.SCHEDULED);
            taskLogMapper.insert(taskLog);

            flag = true;
        } catch (Exception e) {
            log.warn("add task exception task id-{}", task.getTaskId());
            throw new ScheduleSystemException(e.getMessage());
        }

        return flag;
    }

    private void addTaskToCache(Task task) {
        // ????????????????????????????????????key
        String key = task.getTaskType() + "_" + task.getPriority();
        long nextScheduleTime = getNextScheduleTime();

        // ???????????????????????????????????????????????????????????????
        if (task.getExecuteTime() <= System.currentTimeMillis()) {
            cacheService.lRightPush(Constants.TOPIC + key, JSON.toJSONString(task));
        } else if (task.getExecuteTime() <= nextScheduleTime) {
            cacheService.zAdd(Constants.FUTURE + key, JSON.toJSONString(task), task.getExecuteTime());
        }
    }

    private long getNextScheduleTime() {
        // ??????????????????????????????
        if (cacheService.exists(Constants.NEXT_SCHEDULE_TIME)) {
            String nextScheduleTimeStr = cacheService.get(Constants.NEXT_SCHEDULE_TIME);
            log.info("??????????????????NEXT_SCHEDULE_TIME-{}", nextScheduleTimeStr);
            return Long.parseLong(nextScheduleTimeStr);
        } else {
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.MINUTE, systemParamsConfiguration.getReload());
            this.nextScheduleTime = calendar.getTimeInMillis();
            cacheService.set(Constants.NEXT_SCHEDULE_TIME, nextScheduleTime + "");
            log.info("???????????????nextScheduleTime, ??????????????????{}", nextScheduleTime);
            return nextScheduleTime;
        }
    }

    @Override
    @Transactional
    public boolean cancelTask(long taskId) throws TaskNotExistException {
        boolean flag = false;

        Task task = updateDb(taskId, Constants.CANCELLED);
        if (task != null) {
            removeTaskFromCache(task);
            flag = true;
        }
        return flag;
    }

    private void removeTaskFromCache(Task task) {
        String key = task.getTaskType() + "_" + task.getPriority();

        // ??????????????????????????????????????????????????????????????????
        if (task.getExecuteTime() <= System.currentTimeMillis()) {
            cacheService.lRemove(Constants.TOPIC + key, 0, JSON.toJSONString(task));
        } else {
            cacheService.zRemove(Constants.FUTURE + key, JSON.toJSONString(task));
        }
    }

    private Task updateDb(long taskId, int status) throws TaskNotExistException {
        Task task = null;
        try {
            // ???????????????????????????
            taskMapper.deleteById(taskId);
            // ??????????????????????????????????????????
            TaskInfoLogs taskLog = taskLogMapper.selectById(taskId);
            taskLog.setStatus(status);
            taskLogMapper.updateById(taskLog);

            // ???????????????????????????
            task = new Task();
            BeanUtils.copyProperties(taskLog, task);
            task.setExecuteTime(taskLog.getExecuteTime().getTime());
        } catch (Exception e) {
            log.warn("task cancel exception task id-{}", taskId);
            throw new TaskNotExistException(e);
        }
        return task;
    }
}
