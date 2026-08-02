package com.hunt.otziv.metric_snapshots.service;

import java.io.File;
import org.springframework.stereotype.Component;

@Component
public class DiskSpaceUsageProvider {

    public DiskUsage current() {
        File root = new File("/");
        long total = root.getTotalSpace();
        long usable = root.getUsableSpace();
        long used = Math.max(0L, total - usable);
        int usedPercent = total <= 0L ? 0 : (int) Math.min(100L, Math.round(used * 100.0d / total));
        return new DiskUsage(total, used, usable, usedPercent);
    }

    public record DiskUsage(long totalBytes, long usedBytes, long usableBytes, int usedPercent) {
    }
}
