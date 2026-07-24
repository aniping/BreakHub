package com.ateagents.breakhub.domain;

final class BreakpointFieldPath {

    private BreakpointFieldPath() {
    }

    static boolean isValid(String fieldPath) {
        if (fieldPath == null || fieldPath.isEmpty() || fieldPath.length() > 500) {
            return false;
        }
        if (fieldPath.contains("[") || fieldPath.contains("]") || fieldPath.contains("/")
                || fieldPath.contains("$") || fieldPath.contains("@")
                || fieldPath.contains("*") || fieldPath.contains("\\")) {
            return false;
        }
        for (String segment : fieldPath.split("\\.", -1)) {
            if (segment.isBlank() || segment.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }
}
