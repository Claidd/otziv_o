package com.hunt.otziv.p_products.worker_access;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "otziv.worker.cellular-access")
public class WorkerCellularAccessProperties {

    private Mode mode = Mode.OFF;
    private boolean requireMobileDevice = true;
    private List<String> allowedCidrs = new ArrayList<>();

    public enum Mode {
        OFF,
        AUDIT,
        ENFORCE
    }
}
