package org.pvpbotformation;

import java.util.Collections;
import java.util.Set;

/**
 * Accesses pvpbot-groups classes at runtime via reflection.
 * All methods return safe empty defaults if the addon is not installed.
 */
public class GroupsBridge {

    private static final Class<?> GROUP_MANAGER_CLASS;

    static {
        Class<?> clazz = null;
        try {
            clazz = Class.forName("org.pvpbotgroups.GroupManager");
        } catch (ClassNotFoundException ignored) {
        }
        GROUP_MANAGER_CLASS = clazz;
    }

    /** Returns true if pvpbot-groups is loaded in this game instance. */
    public static boolean isGroupsAddonPresent() {
        return GROUP_MANAGER_CLASS != null;
    }

    /**
     * Returns all member bot names in the given group.
     * Returns an empty set if pvpbot-groups is not loaded or the group does not exist.
     */
    @SuppressWarnings("unchecked")
    public static Set<String> getGroupMembers(String group) {
        if (GROUP_MANAGER_CLASS == null) return Collections.emptySet();
        try {
            return (Set<String>) GROUP_MANAGER_CLASS
                    .getMethod("getGroupMembers", String.class)
                    .invoke(null, group);
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /**
     * Returns all known group names.
     * Returns an empty set if pvpbot-groups is not loaded.
     */
    @SuppressWarnings("unchecked")
    public static Set<String> getAllGroupNames() {
        if (GROUP_MANAGER_CLASS == null) return Collections.emptySet();
        try {
            return (Set<String>) GROUP_MANAGER_CLASS
                    .getMethod("getAllGroupNames")
                    .invoke(null);
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /** Returns true if the given group exists in pvpbot-groups. */
    public static boolean groupExists(String group) {
        if (GROUP_MANAGER_CLASS == null) return false;
        try {
            return (boolean) GROUP_MANAGER_CLASS
                    .getMethod("groupExists", String.class)
                    .invoke(null, group);
        } catch (Exception e) {
            return false;
        }
    }
}
