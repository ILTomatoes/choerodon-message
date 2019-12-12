package io.choerodon.notify.api.service.impl;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.alibaba.fastjson.JSON;
import com.zaxxer.hikari.util.UtilityElf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.choerodon.core.exception.CommonException;
import io.choerodon.core.notify.TargetUserType;
import io.choerodon.notify.api.dto.CheckLog;
import io.choerodon.notify.api.dto.DevopsNotificationTransferDataVO;
import io.choerodon.notify.api.dto.MessageDetailDTO;
import io.choerodon.notify.api.service.NotifyCheckLogService;
import io.choerodon.notify.infra.dto.MessageSettingDTO;
import io.choerodon.notify.infra.dto.NotifyCheckLogDTO;
import io.choerodon.notify.infra.dto.TargetUserDTO;
import io.choerodon.notify.infra.feign.AgileFeignClient;
import io.choerodon.notify.infra.feign.DevopsFeginClient;
import io.choerodon.notify.infra.mapper.MessageSettingMapper;
import io.choerodon.notify.infra.mapper.MessageSettingTargetUserMapper;
import io.choerodon.notify.infra.mapper.NotifyCheckLogMapper;
import io.choerodon.notify.infra.mapper.SendSettingMapper;


@Service
public class NotifyCheckLogServiceImpl implements NotifyCheckLogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotifyCheckLogServiceImpl.class);
    private static final String AGILE = "agile";
    private static final ExecutorService executorService = new ThreadPoolExecutor(0, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new UtilityElf.DefaultThreadFactory("notify-upgrade", false));

    private static final Map<String, String> codeMap = new HashMap<>(3);
    private static final Map<String, String> targetUserType = new HashMap<>(5);

    static {
        codeMap.put("issue_assigneed", "issueAssignee");
        codeMap.put("issue_solved", "issueSolve");
        codeMap.put("issue_created", "issueCreate");

        targetUserType.put("assigneer", "assignee");
        targetUserType.put("reporter", "reporter");
        targetUserType.put("users", "specifier");
        targetUserType.put("project_owner", "projectOwner");
    }

    @Autowired
    private NotifyCheckLogMapper notifyCheckLogMapper;
    @Autowired
    private AgileFeignClient agileFeignClient;
    @Autowired
    private SendSettingMapper sendSettingMapper;
    @Autowired
    private MessageSettingMapper messageSettingMapper;
    @Autowired
    private MessageSettingTargetUserMapper messageSettingTargetUserMapper;
    @Autowired
    private DevopsFeginClient devopsFeignClient;

    @Override
    public void checkLog(String version, String type) {
        LOGGER.info("start upgrade task");
        executorService.execute(new UpgradeTask(version, type));
    }

    class UpgradeTask implements Runnable {
        private String version;
        private String type;

        UpgradeTask(String version, String type) {
            this.version = version;
            this.type = type;
        }

        private void transferDevopsData() {
            List<DevopsNotificationTransferDataVO> devopsNotificationVOS = devopsFeignClient.transferData(1L).getBody();
            if (devopsNotificationVOS == null || devopsNotificationVOS.size() == 0) {
                LOGGER.info("No data to migrate");
            }
            MessageSettingDTO messageSettingDTO = new MessageSettingDTO();
            devopsNotificationVOS.stream().forEach(e -> {

                messageSettingDTO.setCode("resourceDeleteConfirmation");
                messageSettingDTO.setProjectId(Objects.isNull(e.getProjectId()) ? null : e.getProjectId());
                messageSettingDTO.setNotifyType("resourceDelete");
                messageSettingDTO.setEnvId(e.getEnvId());
                List<String> recouseNameList = new ArrayList<>();
                if (e.getNotifyTriggerEvent().contains(",")) {
                    recouseNameList = Stream.of(e.getNotifyTriggerEvent().split(",")).collect(Collectors.toList());
                } else {
                    recouseNameList.add(e.getNotifyTriggerEvent());
                }
                List<String> notifyType = new ArrayList<>();
                if (e.getNotifyType().contains(",")) {
                    notifyType = Stream.of(e.getNotifyType().split(",")).collect(Collectors.toList());
                } else {
                    notifyType.add(e.getNotifyType());
                }
                for (String name : recouseNameList) {
                    messageSettingDTO.setEventName(name);
                    for (String type : notifyType) {
                        messageSettingDTO.setEmailEnable(false);
                        messageSettingDTO.setSmsEnable(false);
                        messageSettingDTO.setPmEnable(false);
                        if ("sms".equals(type)) {
                            messageSettingDTO.setSmsEnable(true);
                        }
                        if ("email".equals(type)) {
                            messageSettingDTO.setEmailEnable(true);
                        }
                        if ("pm".equals(type)) {
                            messageSettingDTO.setPmEnable(true);
                        }
                    }
                    messageSettingDTO.setId(null);
                    messageSettingMapper.insert(messageSettingDTO);
                    //插入接收对象
                    MessageSettingDTO condition = new MessageSettingDTO();
                    condition.setEventName(name);
                    condition.setEnvId(messageSettingDTO.getEnvId());
                    MessageSettingDTO messageSettingDTO1 = messageSettingMapper.selectOne(condition);

                    TargetUserDTO targetUserDTO = new TargetUserDTO();
                    targetUserDTO.setMessageSettingId(messageSettingDTO1.getId());
                    if ("specifier".equals(e.getNotifyObject())) {
                        targetUserDTO.setType(TargetUserType.SPECIFIER.getTypeName());
                        e.getUserRelDTOS().stream().forEach(u -> {
                            targetUserDTO.setUserId(u.getUserId());
                            targetUserDTO.setId(null);
                            targetUserDTO.setType("specifier");
                            messageSettingTargetUserMapper.insert(targetUserDTO);
                        });
                    } else if ("owner".equals(e.getNotifyObject())) {
                        targetUserDTO.setId(null);
                        targetUserDTO.setType(TargetUserType.PROJECT_OWNER.getTypeName());
                        messageSettingTargetUserMapper.insert(targetUserDTO);
                    } else if ("handler".equals(e.getNotifyObject())) {
                        targetUserDTO.setId(null);
                        targetUserDTO.setType(TargetUserType.HANDLER.getTypeName());
                        messageSettingTargetUserMapper.insert(targetUserDTO);
                    }
                }
                recouseNameList.clear();
                notifyType.clear();
            });

        }

        @Override
        public void run() {
            try {
                NotifyCheckLogDTO notifyCheckLogDTO = new NotifyCheckLogDTO();
                List<CheckLog> logs = new ArrayList<>();
                notifyCheckLogDTO.setBeginCheckDate(new Date());
                if ("0.20.0".equals(version) && type.equals("devops")) {
                    // todo
                    LOGGER.info("Migration data start");
                    transferDevopsData();
                }
                if ("0.20.0".equals(version) && type.equals("agile")) {
                    syncAgileNotify(logs);
                } else {
                    LOGGER.info("version not matched");
                }

                notifyCheckLogDTO.setLog(JSON.toJSONString(logs));
                notifyCheckLogDTO.setEndCheckDate(new Date());
                notifyCheckLogMapper.insert(notifyCheckLogDTO);
            } catch (Throwable ex) {
                LOGGER.warn("Exception occurred when applying data migration. The ex is: {}", ex);
            }
        }
    }

    void syncAgileNotify(List<CheckLog> logs) {
        LOGGER.info("begin to sync agile notify!");
        List<MessageDetailDTO> messageDetailDTOList;
        try {
            messageDetailDTOList = agileFeignClient.migrateMessageDetail().getBody();
        } catch (Exception e) {
            CheckLog checkLog = new CheckLog();
            checkLog.setContent("Get message of agile!");
            checkLog.setResult("error.get.agile.message.detail");
            logs.add(checkLog);
            throw new CommonException("error.get.agile.message.detail", e);
        }

        if (messageDetailDTOList != null && messageDetailDTOList.size() > 0) {
            Map<Long, List<MessageDetailDTO>> messageDetailMap = messageDetailDTOList.stream().collect(Collectors.groupingBy(MessageDetailDTO::getProjectId));
            for (Map.Entry<Long, List<MessageDetailDTO>> map : messageDetailMap.entrySet()) {
                CheckLog checkLog = new CheckLog();
                checkLog.setContent("begin to sync agile notify projectId:" + map.getKey());
                try {
                    for (MessageDetailDTO v : map.getValue()) {
                        MessageSettingDTO messageSettingDTO = new MessageSettingDTO();
                        messageSettingDTO.setProjectId(map.getKey());
                        messageSettingDTO.setNotifyType(AGILE);
                        messageSettingDTO.setCode(codeMap.get(v.getEvent()));
                        // 1. messageSetting
                        MessageSettingDTO queryDTO = messageSettingMapper.selectOne(messageSettingDTO);
                        if (queryDTO == null) {
                            messageSettingDTO.setPmEnable(true);
                            if (messageSettingMapper.insertSelective(messageSettingDTO) != 1) {
                                throw new CommonException("error.insert.message.send.setting");
                            }
                        } else {
                            messageSettingDTO.setId(queryDTO.getId());
                        }
                        //2. messageSettingTargetUser
                        if (v.getNoticeType().equals("users")) {
                            if (v.getUser() == null || v.getUser().equals("")) {
                                continue;
                            }
                            String[] userIds = v.getUser().split(",");
                            for (String userId : userIds) {
                                if (!userId.equals("") && isInteger(userId)) {
                                    createMessageSettingTargetUser(messageSettingDTO.getId(), targetUserType.get(v.getNoticeType()), Long.valueOf(userId));
                                }
                            }
                        } else {
                            createMessageSettingTargetUser(messageSettingDTO.getId(), targetUserType.get(v.getNoticeType()), null);
                        }
                        checkLog.setResult("Succeed to sync agile notify!");
                    }
                } catch (Exception e) {
                    checkLog.setResult("Failed to sync agile notify!");
                    throw e;
                }
                logs.add(checkLog);
            }
        }

    }

    private void createMessageSettingTargetUser(Long messageSettingId, String noticeType, Long userId) {
        TargetUserDTO targetUserDTO = new TargetUserDTO();
        targetUserDTO.setMessageSettingId(messageSettingId);
        targetUserDTO.setType(noticeType);
        targetUserDTO.setUserId(userId);
        if (messageSettingTargetUserMapper.selectOne(targetUserDTO) == null) {
            if (messageSettingTargetUserMapper.insertSelective(targetUserDTO) != 1) {
                throw new CommonException("error.insert.message.setting.target.user");
            }
        }
    }

    public boolean isInteger(String str) {
        Pattern pattern = Pattern.compile("^[-\\+]?[\\d]*$");
        return pattern.matcher(str).matches();
    }
}
