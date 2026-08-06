package com.tv.live;

/**
 * 设置项数据模型。
 *
 * 类型：
 * - TYPE_ACTION:  点击弹对话框/执行动作（有右侧值显示）
 * - TYPE_TOGGLE:  开关（boolean 值，持久化到 SharedPreferences）
 */
public class SettingItem {

    public static final int TYPE_ACTION = 0;
    public static final int TYPE_TOGGLE = 1;

    public String key;          // 唯一标识
    public String title;        // 标题
    public int type;            // TYPE_ACTION / TYPE_TOGGLE
    public String summary;      // 右侧当前值描述（ACTION 类型用）
    public String prefKey;      // SharedPreferences key（TOGGLE 类型用）
    public boolean prefDefault;// TOGGLE 默认值

    public SettingItem(String key, String title, int type) {
        this.key = key;
        this.title = title;
        this.type = type;
    }

    public SettingItem withSummary(String summary) {
        this.summary = summary;
        return this;
    }

    public SettingItem withToggle(String prefKey, boolean defaultValue) {
        this.prefKey = prefKey;
        this.prefDefault = defaultValue;
        return this;
    }
}
