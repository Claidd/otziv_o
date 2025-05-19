package com.hunt.otziv.text_generator.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DockerUtils {

    public static boolean isRunningInDocker() {
        try {
            String cgroup = Files.readString(Path.of("/proc/1/cgroup"));
            return cgroup.contains("docker") || cgroup.contains("kubepods");
        } catch (IOException e) {
            return false;
        }
    }
}
