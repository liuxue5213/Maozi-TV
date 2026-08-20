package com.tv.live;

import android.content.SharedPreferences;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏分组管理器
 * 从扁平的收藏 ID 列表迁移到分组结构
 */
public class FavoriteGroupManager {
    private static final String PREF_FAV_GROUPS = "favorite_groups";
    private static final String DEFAULT_GROUP = "默认";

    /**
     * 获取所有分组及其频道列表
     * @return Map<分组名, List<频道ID>>
     */
    public static Map<String, List<Integer>> getGroups(Context context) {
        Map<String, List<Integer>> groups = new HashMap<>();
        SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        String jsonStr = prefs.getString(PREF_FAV_GROUPS, null);

        if (jsonStr != null && !jsonStr.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(jsonStr);
                JSONArray groupNames = obj.names();
                if (groupNames != null) {
                    for (int i = 0; i < groupNames.length(); i++) {
                        String groupName = groupNames.getString(i);
                        JSONArray ids = obj.getJSONArray(groupName);
                        List<Integer> idList = new ArrayList<>();
                        for (int j = 0; j < ids.length(); j++) {
                            idList.add(ids.getInt(j));
                        }
                        groups.put(groupName, idList);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 如果没有任何分组，创建默认分组
        if (groups.isEmpty()) {
            groups.put(DEFAULT_GROUP, new ArrayList<>());
        }

        return groups;
    }

    /**
     * 保存分组数据
     */
    public static void saveGroups(Context context, Map<String, List<Integer>> groups) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
                JSONArray ids = new JSONArray();
                for (Integer id : entry.getValue()) {
                    ids.put(id);
                }
                obj.put(entry.getKey(), ids);
            }

            SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_FAV_GROUPS, obj.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 添加频道到指定分组
     */
    public static void addChannelToGroup(Context context, int channelId, String groupName) {
        Map<String, List<Integer>> groups = getGroups(context);

        // 从其他分组中移除该频道（一个频道只能在一个分组中）
        for (List<Integer> channelList : groups.values()) {
            channelList.remove(Integer.valueOf(channelId));
        }

        // 添加到指定分组
        if (!groups.containsKey(groupName)) {
            groups.put(groupName, new ArrayList<>());
        }
        groups.get(groupName).add(channelId);

        saveGroups(context, groups);
    }

    /**
     * 从分组中移除频道
     */
    public static void removeChannelFromAllGroups(Context context, int channelId) {
        Map<String, List<Integer>> groups = getGroups(context);
        for (List<Integer> channelList : groups.values()) {
            channelList.remove(Integer.valueOf(channelId));
        }
        saveGroups(context, groups);
    }

    /**
     * 创建新分组
     */
    public static void createGroup(Context context, String groupName) {
        Map<String, List<Integer>> groups = getGroups(context);
        if (!groups.containsKey(groupName)) {
            groups.put(groupName, new ArrayList<>());
            saveGroups(context, groups);
        }
    }

    /**
     * 重命名分组
     */
    public static void renameGroup(Context context, String oldName, String newName) {
        Map<String, List<Integer>> groups = getGroups(context);
        if (groups.containsKey(oldName)) {
            List<Integer> channels = groups.get(oldName);
            groups.remove(oldName);
            groups.put(newName, channels);
            saveGroups(context, groups);
        }
    }

    /**
     * 删除分组（不会删除频道，只是将它们移到默认分组）
     */
    public static void deleteGroup(Context context, String groupName) {
        Map<String, List<Integer>> groups = getGroups(context);
        if (groups.containsKey(groupName) && !groupName.equals(DEFAULT_GROUP)) {
            List<Integer> channels = groups.get(groupName);
            groups.remove(groupName);

            // 将频道移到默认分组
            if (!groups.containsKey(DEFAULT_GROUP)) {
                groups.put(DEFAULT_GROUP, new ArrayList<>());
            }
            groups.get(DEFAULT_GROUP).addAll(channels);

            saveGroups(context, groups);
        }
    }

    /**
     * 从旧的扁平收藏数据迁移到分组结构
     */
    public static void migrateFromOldFavorites(Context context, String oldFavoritesStr) {
        if (oldFavoritesStr == null || oldFavoritesStr.isEmpty()) {
            return;
        }

        Map<String, List<Integer>> groups = getGroups(context);
        List<Integer> defaultGroup = groups.get(DEFAULT_GROUP);

        String[] ids = oldFavoritesStr.split(",");
        for (String idStr : ids) {
            try {
                int id = Integer.parseInt(idStr.trim());
                if (!defaultGroup.contains(id)) {
                    defaultGroup.add(id);
                }
            } catch (NumberFormatException ignored) {}
        }

        groups.put(DEFAULT_GROUP, defaultGroup);
        saveGroups(context, groups);
    }

    /**
     * 获取某个分组中的所有频道 ID
     */
    public static List<Integer> getChannelsInGroup(Context context, String groupName) {
        Map<String, List<Integer>> groups = getGroups(context);
        return groups.getOrDefault(groupName, new ArrayList<>());
    }

    /**
     * 检查频道是否在任何分组中
     */
    public static boolean isChannelInAnyGroup(Context context, int channelId) {
        Map<String, List<Integer>> groups = getGroups(context);
        for (List<Integer> channelList : groups.values()) {
            if (channelList.contains(channelId)) {
                return true;
            }
        }
        return false;
    }
}
