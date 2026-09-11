package com.hunt.otziv.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hunt.otziv.config.api.ApiExceptionHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.json.JsonMapper;

/** Contract metadata comes from compiled Spring/Jackson declarations, never a parallel TS field list. */
final class ClientApiContractModel {
    final JsonMapper mapper;
    final Path root;
    final Map<String, Object> schemas = new TreeMap<>();
    final Map<String, JavaType> outputTypes = new TreeMap<>();
    final Map<String, JavaType> inputTypes = new TreeMap<>();
    final Map<String, String> sourceHashes = new TreeMap<>();
    final Map<String, String> schemaJavaTypes = new TreeMap<>();
    final Map<String, Method> handlers = new TreeMap<>();
    private final ClassIntrospector serialization;
    private final ClassIntrospector deserialization;

    ClientApiContractModel(JsonMapper mapper, Path root) {
        this.mapper = mapper;
        this.root = root;
        serialization = mapper.serializationConfig().classIntrospectorInstance().forOperation(mapper.serializationConfig());
        deserialization = mapper.deserializationConfig().classIntrospectorInstance().forOperation(mapper.deserializationConfig());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> export() throws Exception {
        Map<String, Object> config = mapper.readValue(root.resolve("contracts/client-api.config.json"), Map.class);
        List<String> prefixes = (List<String>) config.get("pathPrefixes");
        Map<String, Number> statuses = (Map<String, Number>) config.get("statusOverrides");
        Map<String, Object> paths = new TreeMap<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var context = new StaticWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.refresh();
        var mappings = new MappingProbe();
        mappings.setApplicationContext(context);
        mappings.afterPropertiesSet();
        try {
            List<String> controllers = scanner.findCandidateComponents("com.hunt.otziv").stream()
                    .map(b -> b.getBeanClassName()).sorted().toList();
            for (String className : controllers) {
                Class<?> controller = Class.forName(className);
                if (!controller.getProtectionDomain().getCodeSource().getLocation().equals(
                        ApiExceptionHandler.class.getProtectionDomain().getCodeSource().getLocation())) continue;
                for (Method method : Arrays.stream(controller.getDeclaredMethods()).sorted(Comparator.comparing(Method::toGenericString)).toList()) {
                    RequestMappingInfo mapping = mappings.describe(method, controller);
                    if (mapping == null) continue;
                    for (String path : new TreeSet<>(mapping.getPatternValues())) {
                        if (prefixes.stream().noneMatch(path::startsWith)) continue;
                        remember(controller);
                        if (mapping.getMethodsCondition().getMethods().isEmpty()) throw new IllegalStateException("Explicit HTTP verb required: " + method);
                        for (RequestMethod verb : mapping.getMethodsCondition().getMethods()) {
                            String key = verb.name() + " " + path;
                            if (handlers.put(key, method) != null) throw new IllegalStateException("Ambiguous client operation " + key);
                            Map<String, Object> operation = operation(controller, method, verb, path, statuses.get(key));
                            Map<String, Object> verbs = (Map<String, Object>) paths.computeIfAbsent(path, ignored -> new TreeMap<>());
                            verbs.put(verb.name().toLowerCase(Locale.ROOT), operation);
                        }
                    }
                }
            }
        } finally { context.close(); }
        JavaType errorType = mapper.constructType(ApiExceptionHandler.ApiErrorResponse.class);
        schema(errorType, false);
        remember(ApiExceptionHandler.class);
        for (String file : List.of("backend/pom.xml", "contracts/client-api.config.json")) hash(file);
        Map<String, Object> result = object("openapi", "3.1.0", "info", object("title", "Otziv client API", "version", config.get("version")),
                "x-generator-version", config.get("generatorVersion"), "paths", paths,
                "components", object("schemas", schemas, "securitySchemes", object("bearerAuth", object("type", "http", "scheme", "bearer", "bearerFormat", "JWT"))),
                "x-source-sha256", sourceHashes.entrySet().stream()
                        .map(entry -> object("path", entry.getKey(), "sha256", entry.getValue())).toList(), "x-source-fingerprint-normalization", "UTF-8 text with CRLF normalized to LF", "x-schema-java-types", schemaJavaTypes,
                "x-runtime-json", "Spring MVC Jackson 3 message converter; financial writes remain explicit and never replayed by this SDK");
        return result;
    }

    private Map<String, Object> operation(Class<?> controller, Method method, RequestMethod verb, String path, Number statusOverride) {
        Map<String, Object> result = object("operationId", controller.getSimpleName() + "_" + method.getName(),
                "x-java-handler", controller.getName() + "#" + method.getName());
        List<Object> parameters = new ArrayList<>();
        Map<String, Object> parts = new TreeMap<>();
        List<String> requiredParts = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            JavaType type = mapper.constructType(parameter.getParameterizedType());
            RequestBody body = parameter.getAnnotation(RequestBody.class);
            RequestPart part = parameter.getAnnotation(RequestPart.class);
            if (body != null) {
                result.put("requestBody", object("required", body.required(), "content", object("application/json", object("schema", schema(type, true)))));
            } else if (part != null || MultipartFile.class.isAssignableFrom(type.getRawClass())) {
                RequestParam query = parameter.getAnnotation(RequestParam.class);
                String name = part != null ? first(part.name(), part.value(), parameter.getName())
                        : query != null ? first(query.name(), query.value(), parameter.getName()) : parameter.getName();
                parts.put(name, schema(type, true));
                if (part != null ? part.required() : query == null || query.required()) requiredParts.add(name);
            } else {
                PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
                RequestParam query = parameter.getAnnotation(RequestParam.class);
                RequestHeader header = parameter.getAnnotation(RequestHeader.class);
                if (pathVariable == null && query == null && header == null) continue;
                String name = pathVariable != null ? first(pathVariable.name(), pathVariable.value(), parameter.getName())
                        : query != null ? first(query.name(), query.value(), parameter.getName()) : first(header.name(), header.value(), parameter.getName());
                String location = pathVariable != null ? "path" : query != null ? "query" : "header";
                boolean required = pathVariable != null || (query != null ? query.required() && query.defaultValue().equals(ValueConstants.DEFAULT_NONE) : header.required());
                Map<String, Object> description = object("name", name, "in", location, "required", required, "schema", schema(type, true));
                if (query != null && !query.defaultValue().equals(ValueConstants.DEFAULT_NONE)) description.put("x-server-default", query.defaultValue());
                parameters.add(description);
            }
        }
        if (!parts.isEmpty()) result.put("requestBody", object("required", !requiredParts.isEmpty(), "content", object("multipart/form-data", object("schema", object("type", "object", "properties", parts, "required", requiredParts)))));
        result.put("parameters", parameters);
        PreAuthorize permission = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (permission == null) permission = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
        if (permission != null) result.put("x-preauthorize", permission.value());
        result.put("security", path.startsWith("/api/payments/public/") ? List.of() : List.of(object("bearerAuth", List.of())));
        if (path.startsWith("/api/payments/public/")) result.put("x-public-capability", true);
        JavaType output = mapper.constructType(method.getGenericReturnType());
        if (ResponseEntity.class.isAssignableFrom(output.getRawClass())) output = output.containedTypeOrUnknown(0);
        ResponseStatus responseStatus = AnnotatedElementUtils.findMergedAnnotation(method, ResponseStatus.class);
        int status = statusOverride != null ? statusOverride.intValue() : responseStatus != null ? responseStatus.code().value() : 200;
        Map<String, Object> responses = new TreeMap<>();
        Map<String, Object> success = object("description", "Declared controller response");
        if (output.getRawClass() != void.class && output.getRawClass() != Void.class) success.put("content", object("application/json", object("schema", schema(output, false))));
        responses.put(Integer.toString(status), success);
        // Method security may reject before controller advice runs: 401/403 do
        // not promise one body format. Business/validation errors use ApiErrorResponse.
        responses.put("400", object("description", "Validation or domain rejection", "content", object("application/json", object("schema", schema(mapper.constructType(ApiExceptionHandler.ApiErrorResponse.class), false)))));
        responses.put("401", object("description", "Authentication rejected; a write is not automatically retried"));
        responses.put("403", object("description", "Server denied access; authority is not inferred from client state"));
        responses.put("409", object("description", "Conflict; read current state before an explicit user retry", "content", object("application/json", object("schema", schema(mapper.constructType(ApiExceptionHandler.ApiErrorResponse.class), false)))));
        result.put("responses", responses);
        result.put("x-automatic-retry", false);
        return result;
    }

    Map<String, Object> schema(JavaType type, boolean input) {
        Class<?> raw = type.getRawClass();
        if (raw == void.class || raw == Void.class) return object("type", "null");
        if (raw == Object.class) return object("x-java-type", "java.lang.Object", "description", "Unconstrained JSON from an explicitly untyped Java declaration");
        if (raw == UUID.class) return object("type", "string", "format", "uuid");
        if (raw == java.net.URI.class || raw == java.net.URL.class) return object("type", "string", "format", "uri");
        if (raw == String.class || raw == Character.class || raw == char.class) return object("type", "string");
        if (raw == boolean.class || raw == Boolean.class) return object("type", "boolean");
        if (raw == BigDecimal.class || raw == float.class || raw == double.class || raw == Float.class || raw == Double.class) return object("type", "number");
        if (raw.isPrimitive() || Number.class.isAssignableFrom(raw)) return object("type", "integer");
        if (raw == LocalDate.class) return object("type", "string", "format", "date");
        if (raw == LocalDateTime.class) return object("type", "string", "x-date-time-kind", "local");
        if (raw == Instant.class || raw == OffsetDateTime.class || raw == ZonedDateTime.class || Date.class.isAssignableFrom(raw)) return object("type", "string", "format", "date-time");
        if (raw == LocalTime.class) return object("type", "string", "x-date-time-kind", "local-time");
        if (MultipartFile.class.isAssignableFrom(raw) || raw == byte[].class) return object("type", "string", "format", "binary");
        if (type.isArrayType() || type.isCollectionLikeType()) return object("type", "array", "items", schema(type.getContentType(), input));
        if (type.isMapLikeType()) return object("type", "object", "additionalProperties", schema(type.getContentType(), input));
        if (Optional.class.isAssignableFrom(raw)) return nullable(schema(type.containedTypeOrUnknown(0), input));
        remember(raw);
        String name = typeName(type) + (input ? "Input" : "Output");
        if (schemaJavaTypes.containsKey(name) && !schemaJavaTypes.get(name).equals(type.toCanonical())) throw new IllegalStateException("Duplicate schema name " + name);
        schemaJavaTypes.put(name, type.toCanonical());
        if (!schemas.containsKey(name)) {
            Map<String, Object> definition = object("x-java-type", type.toCanonical());
            schemas.put(name, definition);
            (input ? inputTypes : outputTypes).put(name, type);
            if (raw.isEnum()) {
                definition.put("type", "string");
                definition.put("enum", Arrays.stream(raw.getEnumConstants()).map(value -> mapper.convertValue(value, String.class)).toList());
            } else {
                Map<String, Object> properties = new TreeMap<>();
                List<String> required = new ArrayList<>();
                List<String> permissionFields = new ArrayList<>();
                for (BeanPropertyDefinition property : properties(type, input)) {
                    if (input ? !property.couldDeserialize() : !property.couldSerialize()) continue;
                    Map<String, Object> field = schema(property.getPrimaryType(), input);
                    boolean notNull = property.getPrimaryType().isPrimitive() || annotated(property, "NotNull", "NotBlank", "NotEmpty");
                    if (input && annotated(property, "NotBlank")) field = extend(field, "x-not-blank", true);
                    if (input && annotated(property, "Email")) field = extend(field, "format", "email");
                    if (!notNull) field = nullable(field);
                    if (property.getName().endsWith("Kopecks")) field = extend(field, "x-money-unit", "kopecks");
                    properties.put(property.getName(), field);
                    if (input ? property.isRequired() || annotated(property, "NotNull", "NotBlank", "NotEmpty") : !omitsNull(raw, property)) required.add(property.getName());
                    if (property.getPrimaryType().getRawClass() == boolean.class && property.getName().matches("^(can|allow|payable|clientReportable).*")) permissionFields.add(property.getName());
                }
                definition.put("type", "object"); definition.put("properties", properties); definition.put("required", required);
                definition.put("additionalProperties", true);
                if (!permissionFields.isEmpty()) definition.put("x-server-permission-fields", permissionFields);
            }
        }
        return object("$ref", "#/components/schemas/" + name);
    }

    List<BeanPropertyDefinition> properties(JavaType type, boolean input) {
        ClassIntrospector introspector = input ? deserialization : serialization;
        var annotations = introspector.introspectClassAnnotations(type);
        var bean = input ? introspector.introspectForDeserialization(type, annotations) : introspector.introspectForSerialization(type, annotations);
        return bean.findProperties().stream().sorted(Comparator.comparing(BeanPropertyDefinition::getName)).toList();
    }

    private boolean annotated(BeanPropertyDefinition property, String... names) {
        var member = property.getPrimaryMember();
        if (member == null) return false;
        for (String name : names) {
            try {
                @SuppressWarnings("unchecked") Class<? extends java.lang.annotation.Annotation> annotation =
                        (Class<? extends java.lang.annotation.Annotation>) Class.forName("jakarta.validation.constraints." + name);
                if (member.getAnnotation(annotation) != null) return true;
            } catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
        }
        return false;
    }

    private boolean omitsNull(Class<?> raw, BeanPropertyDefinition property) {
        JsonInclude inclusion = property.getPrimaryMember() == null ? null : property.getPrimaryMember().getAnnotation(JsonInclude.class);
        if (inclusion == null) inclusion = raw.getAnnotation(JsonInclude.class);
        return inclusion != null && inclusion.value() != JsonInclude.Include.ALWAYS && inclusion.value() != JsonInclude.Include.USE_DEFAULTS;
    }

    private static String typeName(JavaType type) {
        Class<?> raw = type.getRawClass();
        String name = (raw.getEnclosingClass() == null ? "" : raw.getEnclosingClass().getSimpleName()) + raw.getSimpleName();
        for (int i = 0; i < type.containedTypeCount(); i++) name += "Of" + typeName(type.containedTypeOrUnknown(i));
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private void remember(Class<?> type) {
        while (type.getEnclosingClass() != null) type = type.getEnclosingClass();
        if (type.getName().startsWith("com.hunt.otziv.")) hash("backend/src/main/java/" + type.getName().replace('.', '/') + ".java");
    }

    private void hash(String relative) {
        if (sourceHashes.containsKey(relative)) return;
        try { sourceHashes.put(relative, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readString(root.resolve(relative)).replace("\r\n", "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)))); }
        catch (Exception error) { throw new IllegalStateException("Cannot fingerprint contract source " + relative, error); }
    }

    static Map<String, Object> nullable(Map<String, Object> value) { return object("anyOf", List.of(value, object("type", "null"))); }
    static Map<String, Object> extend(Map<String, Object> value, String key, Object extra) { var result = new LinkedHashMap<>(value); result.put(key, extra); return result; }
    static Map<String, Object> object(Object... fields) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private static String first(String... values) { return Arrays.stream(values).filter(value -> !value.isEmpty()).findFirst().orElseThrow(); }
    private static final class MappingProbe extends RequestMappingHandlerMapping {
        RequestMappingInfo describe(Method method, Class<?> controller) { return getMappingForMethod(method, controller); }
    }
}
