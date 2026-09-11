package com.hunt.otziv.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

/** Export explicitly with -Dclient.contract.export=true; ordinary verification is read-only. */
class ClientApiContractExportTest {
    @Test
    void compiledSpringMappingsAndMvcJacksonPropertiesMatchCheckedInContract() throws Exception {
        Path root = Path.of(System.getProperty("client.contract.root", "..")).toAbsolutePath().normalize();
        var mapper = new JacksonJsonHttpMessageConverter().getMapper();
        var model = new ClientApiContractModel(mapper, root);
        Map<String, Object> actual = model.export();
        Path target = root.resolve("contracts/generated/client-api.openapi.json");
        if (Boolean.getBoolean("client.contract.export")) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
        }
        assertThat(Files.exists(target)).as("Run the explicit client contract export before verification").isTrue();
        assertThat(mapper.readTree(target)).isEqualTo(mapper.valueToTree(actual));
        assertThat(model.handlers.size()).isGreaterThan(80);
        assertThat(model.schemas.size()).isGreaterThan(80);
        assertThat(model.inputTypes).isNotEmpty();
        assertThat(model.outputTypes).isNotEmpty();
    }
}
