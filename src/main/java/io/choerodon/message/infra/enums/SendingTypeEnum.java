package io.choerodon.message.infra.enums;

public enum SendingTypeEnum {
    EMAIL("email"),
    SMS("sms"),
    WEB("web"),
    WH("webhook"),
    WHJSON("webhook-json");

    private String value;

    SendingTypeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isInclude(String key) {
        boolean include = false;
        for (SendingTypeEnum e : SendingTypeEnum.values()) {
            if (e.getValue().equalsIgnoreCase(key)) {
                include = true;
                break;
            }
        }
        return include;
    }
}
