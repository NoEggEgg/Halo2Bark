package com.wuqishi.halo.bark.transport;

/**
 * 单台设备的投递结果。
 *
 * <p>只记录设备名与结果，不记录设备 Key，便于直接用于日志与界面提示（PRD 5.8）。
 */
public record DeviceResult(
        String deviceName,
        boolean success,
        DeliveryFailure failure,
        String message,
        int attempts,
        long elapsedMillis) {

    /** 未命名设备在结果中的显示名。 */
    public static final String UNNAMED_DEVICE = "(未命名设备)";

    public DeviceResult {
        deviceName = deviceName == null || deviceName.isBlank() ? UNNAMED_DEVICE : deviceName;
        message = message == null ? "" : message;
        // 强制成功 / 失败不变量：成功必有空 failure，失败必有非空 failure。矛盾态会令
        // DeviceTester.mapFailure 的 failure() switch 抛 NPE，并误导聚合结果。
        if ((success && failure != null) || (!success && failure == null)) {
            throw new IllegalArgumentException("success 与 failure 不一致：success=" + success
                    + ", failure=" + failure);
        }
    }

    /** 构造成功结果。 */
    public static DeviceResult ok(String deviceName, int attempts, long elapsedMillis) {
        return new DeviceResult(deviceName, true, null, "", attempts, elapsedMillis);
    }

    /** 构造失败结果。 */
    public static DeviceResult failed(String deviceName, DeliveryFailure failure, String message,
            int attempts, long elapsedMillis) {
        return new DeviceResult(deviceName, false, failure, message, attempts, elapsedMillis);
    }
}
