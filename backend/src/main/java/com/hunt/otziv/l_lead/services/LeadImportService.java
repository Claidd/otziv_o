package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadImportTelephonePool;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.LeadImportTelephonePoolRepository;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.utils.LeadPhoneNormalizer;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import com.hunt.otziv.uploads.service.FileUploadGuard;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LeadImportService {

    private static final String DEFAULT_CITY = "Нет";
    private static final String DEFAULT_STATUS = LeadStatus.NEW.title;
    private static final int MAX_ERROR_SAMPLES = 8;
    private static final int PHONE_CHUNK_SIZE = 900;
    private static final int HEADER_SCAN_LIMIT = 30;
    private static final Pattern SCIENTIFIC_NUMBER = Pattern.compile("[+-]?\\d+(?:[.,]\\d+)?[eE][+-]?\\d+");

    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("telephone_lead", "telephone_lead"),
            Map.entry("phone", "telephone_lead"),
            Map.entry("phone_lead", "telephone_lead"),
            Map.entry("telephone", "telephone_lead"),
            Map.entry("номер", "telephone_lead"),
            Map.entry("наименование", "company_name"),
            Map.entry("название", "company_name"),
            Map.entry("компания", "company_name"),
            Map.entry("company", "company_name"),
            Map.entry("company_name", "company_name"),
            Map.entry("телефоны", "phones"),
            Map.entry("телефон", "phones"),
            Map.entry("phones", "phones"),
            Map.entry("мобильные", "mobile_phones"),
            Map.entry("мобильный", "mobile_phones"),
            Map.entry("мобильные_телефоны", "mobile_phones"),
            Map.entry("мобильный_телефон", "mobile_phones"),
            Map.entry("mobile", "mobile_phones"),
            Map.entry("mobile_phone", "mobile_phones"),
            Map.entry("mobile_phones", "mobile_phones"),
            Map.entry("whatsapp", "whatsapp_phones"),
            Map.entry("whats_app", "whatsapp_phones"),
            Map.entry("ватсап", "whatsapp_phones"),
            Map.entry("вацап", "whatsapp_phones"),
            Map.entry("whatsapp_phones", "whatsapp_phones"),
            Map.entry("емейлы", "emails"),
            Map.entry("емейл", "emails"),
            Map.entry("email", "emails"),
            Map.entry("emails", "emails"),
            Map.entry("e_mail", "emails"),
            Map.entry("почта", "emails"),
            Map.entry("сайты", "websites"),
            Map.entry("сайт", "websites"),
            Map.entry("site", "websites"),
            Map.entry("sites", "websites"),
            Map.entry("website", "websites"),
            Map.entry("websites", "websites"),
            Map.entry("vk", "vk_url"),
            Map.entry("вк", "vk_url"),
            Map.entry("vkontakte", "vk_url"),
            Map.entry("vk_url", "vk_url"),
            Map.entry("tg", "telegram_url"),
            Map.entry("telegram", "telegram_url"),
            Map.entry("телеграм", "telegram_url"),
            Map.entry("telegram_url", "telegram_url"),
            Map.entry("отрасли", "industries"),
            Map.entry("отрасль", "industries"),
            Map.entry("industry", "industries"),
            Map.entry("industries", "industries"),
            Map.entry("тип", "company_type"),
            Map.entry("company_type", "company_type"),
            Map.entry("type", "company_type"),
            Map.entry("регион", "region"),
            Map.entry("region", "region"),
            Map.entry("адрес", "address"),
            Map.entry("address", "address"),
            Map.entry("city_lead", "city_lead"),
            Map.entry("city", "city_lead"),
            Map.entry("город", "city_lead"),
            Map.entry("comments_lead", "comments_lead"),
            Map.entry("comments_lea", "comments_lead"),
            Map.entry("comment", "comments_lead"),
            Map.entry("comments", "comments_lead"),
            Map.entry("lid_status", "lid_status"),
            Map.entry("lead_status", "lid_status"),
            Map.entry("status", "lid_status"),
            Map.entry("create_date", "create_date"),
            Map.entry("created_at", "create_date"),
            Map.entry("update_status", "update_status"),
            Map.entry("updated_at", "update_status"),
            Map.entry("date_new_try", "date_new_try"),
            Map.entry("operator_id", "operator_id"),
            Map.entry("manager_id", "manager_id"),
            Map.entry("marketolog_id", "marketolog_id"),
            Map.entry("telephone_id", "telephone_id")
    );

    private final LeadsRepository leadsRepository;
    private final OperatorRepository operatorRepository;
    private final ManagerRepository managerRepository;
    private final MarketologRepository marketologRepository;
    private final TelephoneRepository telephoneRepository;
    private final LeadImportTelephonePoolRepository telephonePoolRepository;
    private final FileUploadGuard fileUploadGuard;

    @Transactional
    public LeadImportResult importLeads(MultipartFile file) {
        return importLeads(file, LeadImportOptions.empty());
    }

    @Transactional
    public LeadImportResult importLeads(MultipartFile file, LeadImportOptions options) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Файл не выбран");
        }
        LeadImportOptions importOptions = options == null ? LeadImportOptions.empty() : options.normalized();

        List<List<String>> rows = readRows(file);
        if (rows.isEmpty()) {
            throw badRequest("Файл не содержит лидов");
        }

        int headerRowIndex = findHeaderRowIndex(rows);
        boolean hasHeader = headerRowIndex >= 0;
        Map<String, Integer> headers = hasHeader ? headerIndexes(rows.get(headerRowIndex)) : Map.of();
        int firstDataRow = hasHeader ? headerRowIndex + 1 : 0;

        int totalRows = 0;
        int skippedDuplicates = 0;
        int skippedWithoutPhones = 0;
        int skippedInvalid = 0;
        List<String> errors = new ArrayList<>();
        List<ImportedLeadRow> importedRows = new ArrayList<>();
        Set<String> seenPhones = new HashSet<>();

        for (int index = firstDataRow; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (!hasContent(row)) {
                continue;
            }

            totalRows++;
            try {
                ImportedLeadRow importedRow = toImportedRow(row, hasHeader, headers, index + 1);
                Set<String> rowPhoneVariants = phoneVariants(importedRow);
                if (rowPhoneVariants.stream().anyMatch(seenPhones::contains)) {
                    skippedDuplicates++;
                    continue;
                }
                seenPhones.addAll(rowPhoneVariants);
                importedRows.add(importedRow);
            } catch (RowWithoutPhoneException exception) {
                skippedWithoutPhones++;
            } catch (RowImportException exception) {
                skippedInvalid++;
                addError(errors, exception.getMessage());
            }
        }

        if (importedRows.isEmpty()) {
            return new LeadImportResult(totalRows, 0, skippedDuplicates, skippedWithoutPhones, skippedInvalid, errors, List.of());
        }

        Set<String> existingTelephoneLeads = findExistingTelephoneLeads(importedRows);
        Map<Long, Operator> operators = new HashMap<>();
        Map<Long, Manager> managers = new HashMap<>();
        Map<Long, Marketolog> marketologs = new HashMap<>();
        Map<Long, Telephone> telephones = new HashMap<>();
        List<Lead> leadsToSave = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        ImportAssignmentPlan assignmentPlan = assignmentPlan(importOptions);
        Operator selectedOperator = resolveOperator(importOptions.operatorId());
        Marketolog selectedMarketolog = resolveMarketolog(importOptions.marketologId());
        Map<Long, ManagerAssignmentCounter> managerCounters = new LinkedHashMap<>();

        for (ImportedLeadRow importedRow : importedRows) {
            if (hasExistingPhone(existingTelephoneLeads, importedRow)) {
                skippedDuplicates++;
                continue;
            }

            try {
                ImportAssignment assignment = assignmentPlan.next();
                Manager assignedManager = assignment.manager() != null
                        ? assignment.manager()
                        : optionalManager(managers, importedRow.managerId(), importedRow.rowNumber());
                Telephone assignedTelephone = assignment.telephone() != null
                        ? assignment.telephone()
                        : optionalTelephone(telephones, importedRow.telephoneId(), importedRow.rowNumber());
                Operator assignedOperator = selectedOperator != null
                        ? selectedOperator
                        : optionalOperator(operators, importedRow.operatorId(), importedRow.rowNumber());
                Marketolog assignedMarketolog = selectedMarketolog != null
                        ? selectedMarketolog
                        : optionalMarketolog(marketologs, importedRow.marketologId(), importedRow.rowNumber());

                leadsToSave.add(Lead.builder()
                        .telephoneLead(importedRow.telephoneLead())
                        .companyName(importedRow.companyName())
                        .phones(importedRow.phones())
                        .mobilePhones(importedRow.mobilePhones())
                        .whatsappPhones(importedRow.whatsappPhones())
                        .emails(importedRow.emails())
                        .websites(importedRow.websites())
                        .vkUrl(importedRow.vkUrl())
                        .telegramUrl(importedRow.telegramUrl())
                        .industries(importedRow.industries())
                        .companyType(importedRow.companyType())
                        .region(importedRow.region())
                        .address(importedRow.address())
                        .cityLead(importedRow.cityLead())
                        .commentsLead(importedRow.commentsLead())
                        .lidStatus(importedRow.lidStatus())
                        .createDate(today)
                        .updateStatus(now)
                        .dateNewTry(today)
                        .operator(assignedOperator)
                        .manager(assignedManager)
                        .marketolog(assignedMarketolog)
                        .telephone(assignedTelephone)
                        .build());
                addManagerAssignment(managerCounters, assignedManager);
            } catch (RowImportException exception) {
                skippedInvalid++;
                addError(errors, exception.getMessage());
            }
        }

        int added = 0;
        if (!leadsToSave.isEmpty()) {
            for (Lead ignored : leadsRepository.saveAll(leadsToSave)) {
                added++;
            }
        }

        return new LeadImportResult(
                totalRows,
                added,
                skippedDuplicates,
                skippedWithoutPhones,
                skippedInvalid,
                errors,
                managerAssignments(managerCounters)
        );
    }

    private ImportedLeadRow toImportedRow(
            List<String> row,
            boolean hasHeader,
            Map<String, Integer> headers,
            int rowNumber
    ) {
        String companyName = cleanCell(cell(row, hasHeader, headers, "company_name", -1));
        String phones = cleanCell(cell(row, hasHeader, headers, "phones", -1));
        String mobilePhones = cleanCell(cell(row, hasHeader, headers, "mobile_phones", -1));
        String whatsappPhones = cleanCell(cell(row, hasHeader, headers, "whatsapp_phones", -1));
        String emails = cleanCell(cell(row, hasHeader, headers, "emails", -1));
        String websites = cleanCell(cell(row, hasHeader, headers, "websites", -1));
        String vkUrl = cleanCell(cell(row, hasHeader, headers, "vk_url", -1));
        String telegramUrl = cleanCell(cell(row, hasHeader, headers, "telegram_url", -1));
        String industries = cleanCell(cell(row, hasHeader, headers, "industries", -1));
        String companyType = cleanCell(cell(row, hasHeader, headers, "company_type", -1));
        String region = cleanCell(cell(row, hasHeader, headers, "region", -1));
        String address = cleanCell(cell(row, hasHeader, headers, "address", -1));
        String legacyTelephoneLead = cleanCell(cell(row, hasHeader, headers, "telephone_lead", 0));

        String telephoneLead = requiredPhone(
                firstPhoneCandidate(whatsappPhones, mobilePhones, phones, legacyTelephoneLead),
                rowNumber
        );

        String cityLead = valueOrDefault(cell(row, hasHeader, headers, "city_lead", 1), DEFAULT_CITY);
        String commentsLead = cleanCell(cell(row, hasHeader, headers, "comments_lead", 2));
        String lidStatus = parseStatusOrDefault(cell(row, hasHeader, headers, "lid_status", 3), DEFAULT_STATUS, rowNumber);
        LocalDate createDate = null;
        LocalDateTime updateStatus = null;
        LocalDate dateNewTry = null;
        Long operatorId = parseLongOrNull(cell(row, hasHeader, headers, "operator_id", 7), "operator_id", rowNumber);
        Long managerId = parseLongOrNull(cell(row, hasHeader, headers, "manager_id", 8), "manager_id", rowNumber);
        Long marketologId = parseLongOrNull(cell(row, hasHeader, headers, "marketolog_id", 9), "marketolog_id", rowNumber);
        Long telephoneId = parseLongOrNull(cell(row, hasHeader, headers, "telephone_id", 10), "telephone_id", rowNumber);

        return new ImportedLeadRow(
                rowNumber,
                telephoneLead,
                companyName,
                phones,
                mobilePhones,
                whatsappPhones,
                emails,
                websites,
                vkUrl,
                telegramUrl,
                industries,
                companyType,
                region,
                address,
                cityLead,
                commentsLead,
                lidStatus,
                createDate,
                updateStatus,
                dateNewTry,
                operatorId,
                managerId,
                marketologId,
                telephoneId
        );
    }

    private List<List<String>> readRows(MultipartFile file) {
        String extension = fileUploadGuard.requireSupportedImportFile(file);
        try {
            if ("xlsx".equals(extension) || "xls".equals(extension)) {
                List<List<String>> rows = readWorkbookRows(file);
                fileUploadGuard.requireImportRowLimit(rows.size());
                return rows;
            }

            if ("csv".equals(extension) || "tsv".equals(extension)) {
                List<List<String>> rows = readDelimitedRows(file);
                fileUploadGuard.requireImportRowLimit(rows.size());
                return rows;
            }
        } catch (IOException exception) {
            throw badRequest("Файл не удалось прочитать");
        }

        throw badRequest("Поддерживаются только CSV, TSV, XLS и XLSX");
    }

    private List<List<String>> readWorkbookRows(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }

            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();

            for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || row.getLastCellNum() < 0) {
                    continue;
                }

                List<String> values = new ArrayList<>();
                for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
                    values.add(cleanCell(formattedCellValue(row.getCell(cellIndex), formatter, evaluator)));
                }

                if (hasContent(values)) {
                    rows.add(values);
                }
            }

            return rows;
        } catch (Exception exception) {
            throw badRequest("Excel-файл не удалось прочитать");
        }
    }

    private String formattedCellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return NumberToTextConverter.toText(cell.getNumericCellValue());
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private List<List<String>> readDelimitedRows(MultipartFile file) throws IOException {
        String text = decode(file.getBytes());
        char delimiter = detectDelimiter(text);
        return parseDelimitedRows(text, delimiter);
    }

    private List<List<String>> parseDelimitedRows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);

            if (quoted) {
                if (current == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        cell.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(current);
                }
                continue;
            }

            if (current == '"') {
                quoted = true;
            } else if (current == delimiter) {
                row.add(cleanCell(cell.toString()));
                cell.setLength(0);
            } else if (current == '\n' || current == '\r') {
                row.add(cleanCell(cell.toString()));
                cell.setLength(0);
                if (hasContent(row)) {
                    rows.add(row);
                }
                row = new ArrayList<>();
                if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
            } else {
                cell.append(current);
            }
        }

        row.add(cleanCell(cell.toString()));
        if (hasContent(row)) {
            rows.add(row);
        }

        return rows;
    }

    private Set<String> findExistingTelephoneLeads(List<ImportedLeadRow> rows) {
        Set<String> importPhoneVariants = rows.stream()
                .flatMap(row -> phoneVariants(row).stream())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<String> phoneVariants = List.copyOf(importPhoneVariants);
        Set<String> existingPhones = new LinkedHashSet<>();

        for (int start = 0; start < phoneVariants.size(); start += PHONE_CHUNK_SIZE) {
            int end = Math.min(start + PHONE_CHUNK_SIZE, phoneVariants.size());
            existingPhones.addAll(leadsRepository.findExistingTelephoneLeads(phoneVariants.subList(start, end)));
        }

        for (LeadsRepository.LeadPhoneProjection existingLead : leadsRepository.findAllLeadPhonesForDuplicateScan()) {
            Set<String> existingLeadVariants = phoneVariants(
                    existingLead.getTelephoneLead(),
                    existingLead.getPhones(),
                    existingLead.getMobilePhones(),
                    existingLead.getWhatsappPhones()
            );
            for (String variant : existingLeadVariants) {
                if (importPhoneVariants.contains(variant)) {
                    existingPhones.add(variant);
                }
            }
        }

        return existingPhones;
    }

    private boolean hasExistingPhone(Set<String> existingTelephoneLeads, ImportedLeadRow importedRow) {
        return phoneVariants(importedRow).stream().anyMatch(existingTelephoneLeads::contains);
    }

    private Set<String> phoneVariants(ImportedLeadRow importedRow) {
        return phoneVariants(
                importedRow.telephoneLead(),
                importedRow.phones(),
                importedRow.mobilePhones(),
                importedRow.whatsappPhones()
        );
    }

    private Set<String> phoneVariants(String... values) {
        Set<String> variants = new LinkedHashSet<>();
        for (String value : values) {
            for (String part : splitMultiValue(value)) {
                String normalized = LeadPhoneNormalizer.normalize(part);
                if (!normalized.isBlank()) {
                    variants.addAll(LeadPhoneNormalizer.variants(normalized));
                }
            }
        }
        return variants;
    }

    private List<String> splitMultiValue(String value) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (String segment : result.split("[;\\r\\n]+")) {
            String cleanSegment = cleanCell(segment);
            if (cleanSegment.isBlank()) {
                continue;
            }
            if (SCIENTIFIC_NUMBER.matcher(cleanSegment).matches()) {
                values.add(cleanSegment);
                continue;
            }
            for (String part : cleanSegment.split(",")) {
                String cleanPart = cleanCell(part);
                if (!cleanPart.isBlank()) {
                    values.add(cleanPart);
                }
            }
        }
        return values;
    }

    private Operator optionalOperator(Map<Long, Operator> cache, Long id, int rowNumber) {
        if (id == null) {
            return null;
        }

        if (!cache.containsKey(id)) {
            cache.put(id, operatorRepository.findById(id).orElse(null));
        }

        Operator operator = cache.get(id);
        if (operator == null) {
            throw rowError(rowNumber, "оператор не найден: " + id);
        }
        return operator;
    }

    private Manager optionalManager(Map<Long, Manager> cache, Long id, int rowNumber) {
        if (id == null) {
            return null;
        }

        if (!cache.containsKey(id)) {
            cache.put(id, managerRepository.findById(id).orElse(null));
        }

        Manager manager = cache.get(id);
        if (manager == null) {
            throw rowError(rowNumber, "менеджер не найден: " + id);
        }
        return manager;
    }

    private Marketolog optionalMarketolog(Map<Long, Marketolog> cache, Long id, int rowNumber) {
        if (id == null) {
            return null;
        }

        if (!cache.containsKey(id)) {
            cache.put(id, marketologRepository.findById(id).orElse(null));
        }

        Marketolog marketolog = cache.get(id);
        if (marketolog == null) {
            throw rowError(rowNumber, "маркетолог не найден: " + id);
        }
        return marketolog;
    }

    private Telephone optionalTelephone(Map<Long, Telephone> cache, Long id, int rowNumber) {
        if (id == null) {
            return null;
        }

        if (!cache.containsKey(id)) {
            cache.put(id, telephoneRepository.findById(id).orElse(null));
        }

        Telephone telephone = cache.get(id);
        if (telephone == null) {
            throw rowError(rowNumber, "телефон рассылки не найден: " + id);
        }
        return telephone;
    }

    private Operator resolveOperator(Long id) {
        if (id == null) {
            return null;
        }
        return operatorRepository.findById(id)
                .orElseThrow(() -> badRequest("Оператор не найден: " + id));
    }

    private Marketolog resolveMarketolog(Long id) {
        if (id == null) {
            return null;
        }
        return marketologRepository.findById(id)
                .orElseThrow(() -> badRequest("Маркетолог не найден: " + id));
    }

    private ImportAssignmentPlan assignmentPlan(LeadImportOptions options) {
        List<Long> managerIds = options.managerIds();
        if (managerIds.isEmpty()) {
            return ImportAssignmentPlan.empty();
        }

        Map<Long, Manager> selectedManagers = new LinkedHashMap<>();
        for (Long managerId : managerIds) {
            Manager manager = managerRepository.findById(managerId)
                    .orElseThrow(() -> badRequest("Менеджер не найден: " + managerId));
            selectedManagers.put(managerId, manager);
        }

        Map<Long, List<Telephone>> telephonesByManager = new LinkedHashMap<>();
        selectedManagers.keySet().forEach(managerId -> telephonesByManager.put(managerId, new ArrayList<>()));

        for (LeadImportTelephonePool pool : telephonePoolRepository.findActiveByManagerIds(selectedManagers.keySet())) {
            Long managerId = pool.getManager() != null ? pool.getManager().getId() : null;
            Telephone telephone = pool.getTelephone();
            if (managerId != null && telephone != null && telephonesByManager.containsKey(managerId)) {
                telephonesByManager.get(managerId).add(telephone);
            }
        }

        List<Long> managersWithoutTelephones = telephonesByManager.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        if (!managersWithoutTelephones.isEmpty()) {
            throw badRequest("Для менеджеров не настроены телефоны импорта: " + managersWithoutTelephones);
        }

        List<ManagerPhonePool> pools = selectedManagers.entrySet().stream()
                .map(entry -> new ManagerPhonePool(entry.getValue(), telephonesByManager.get(entry.getKey())))
                .toList();
        return new ImportAssignmentPlan(pools);
    }

    private void addManagerAssignment(Map<Long, ManagerAssignmentCounter> counters, Manager manager) {
        if (manager == null || manager.getId() == null) {
            return;
        }

        counters.computeIfAbsent(manager.getId(), ignored -> new ManagerAssignmentCounter(manager)).increment();
    }

    private List<LeadImportManagerAssignment> managerAssignments(Map<Long, ManagerAssignmentCounter> counters) {
        return counters.values().stream()
                .map(counter -> new LeadImportManagerAssignment(
                        counter.manager().getId(),
                        managerName(counter.manager()),
                        counter.count()
                ))
                .toList();
    }

    private String managerName(Manager manager) {
        if (manager == null) {
            return "";
        }
        if (manager.getUser() != null) {
            String fio = cleanCell(manager.getUser().getFio());
            if (!fio.isBlank()) {
                return fio;
            }
            String username = cleanCell(manager.getUser().getUsername());
            if (!username.isBlank()) {
                return username;
            }
        }
        return "ID " + manager.getId();
    }

    private int findHeaderRowIndex(List<List<String>> rows) {
        int limit = Math.min(rows.size(), HEADER_SCAN_LIMIT);
        for (int index = 0; index < limit; index++) {
            if (hasHeader(rows.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean hasHeader(List<String> row) {
        Set<String> normalizedHeaders = new HashSet<>();
        for (String value : row) {
            String canonical = HEADER_ALIASES.get(normalizeHeader(value));
            if (canonical != null) {
                normalizedHeaders.add(canonical);
            }
        }
        return normalizedHeaders.contains("telephone_lead")
                || (normalizedHeaders.size() >= 2 && (
                        normalizedHeaders.contains("company_name")
                                || normalizedHeaders.contains("phones")
                                || normalizedHeaders.contains("mobile_phones")
                                || normalizedHeaders.contains("whatsapp_phones")
                ));
    }

    private Map<String, Integer> headerIndexes(List<String> row) {
        Map<String, Integer> headers = new LinkedHashMap<>();
        for (int index = 0; index < row.size(); index++) {
            String canonical = HEADER_ALIASES.get(normalizeHeader(row.get(index)));
            if (canonical != null) {
                headers.putIfAbsent(canonical, index);
            }
        }
        return headers;
    }

    private String cell(List<String> row, boolean hasHeader, Map<String, Integer> headers, String header, int fallbackIndex) {
        int index = fallbackIndex;
        if (hasHeader) {
            Integer headerIndex = headers.get(header);
            if (headerIndex == null) {
                return "";
            }
            index = headerIndex;
        }

        if (index < 0 || index >= row.size()) {
            return "";
        }
        return cleanCell(row.get(index));
    }

    private String valueOrDefault(String value, String defaultValue) {
        String result = cleanCell(value);
        return result.isBlank() ? defaultValue : result;
    }

    private String requiredPhone(String value, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            throw withoutPhone(rowNumber);
        }

        String normalized = LeadPhoneNormalizer.normalize(result);
        if (!isImportablePhone(normalized)) {
            throw withoutPhone(rowNumber);
        }
        return normalized;
    }

    private boolean isImportablePhone(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.length() >= 10 && digits.length() <= 15;
    }

    private String firstPhoneCandidate(String... values) {
        for (String value : values) {
            List<String> parts = splitMultiValue(value);
            if (!parts.isEmpty()) {
                return parts.get(0);
            }
        }
        return "";
    }

    private String parseStatusOrDefault(String value, String defaultValue, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            return defaultValue;
        }

        for (LeadStatus status : LeadStatus.values()) {
            if (status.title.equalsIgnoreCase(result) || status.name().equalsIgnoreCase(result)) {
                return status.title;
            }
        }

        throw rowError(rowNumber, "неизвестный статус лида: " + result);
    }

    private Long parseLongOrNull(String value, String field, int rowNumber) {
        String result = cleanCell(value);
        if (result.isBlank()) {
            return null;
        }

        Long longValue = parseWholeNumber(result);
        if (longValue == null) {
            throw rowError(rowNumber, field + " должен быть целым числом");
        }
        return longValue;
    }

    private Long parseWholeNumber(String value) {
        String normalized = value.trim()
                .replace("\u00A0", "")
                .replace(" ", "")
                .replace(',', '.');

        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            try {
                BigDecimal decimal = new BigDecimal(normalized);
                BigDecimal rounded = decimal.setScale(0, RoundingMode.UNNECESSARY);
                return rounded.longValueExact();
            } catch (ArithmeticException | NumberFormatException exception) {
                return null;
            }
        }
    }

    private boolean hasContent(List<String> row) {
        return row.stream().anyMatch(value -> !cleanCell(value).isBlank());
    }

    private String cleanCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\uFEFF", "").replace('\u00A0', ' ').trim();
    }

    private String normalizeHeader(String value) {
        return cleanCell(value)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .replace("\uFEFF", "");
        } catch (CharacterCodingException exception) {
            return Charset.forName("windows-1251").decode(ByteBuffer.wrap(bytes)).toString().replace("\uFEFF", "");
        }
    }

    private char detectDelimiter(String text) {
        int semicolons = 0;
        int commas = 0;
        int tabs = 0;
        boolean quoted = false;
        int lines = 0;

        for (int index = 0; index < text.length() && lines < 6; index++) {
            char current = text.charAt(index);
            if (current == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (current == ';') {
                    semicolons++;
                } else if (current == ',') {
                    commas++;
                } else if (current == '\t') {
                    tabs++;
                } else if (current == '\n') {
                    lines++;
                }
            }
        }

        if (tabs > semicolons && tabs > commas) {
            return '\t';
        }
        return semicolons >= commas ? ';' : ',';
    }

    private void addError(List<String> errors, String message) {
        if (errors.size() < MAX_ERROR_SAMPLES) {
            errors.add(message);
        }
    }

    private RowImportException rowError(int rowNumber, String message) {
        return new RowImportException("Строка " + rowNumber + ": " + message);
    }

    private RowWithoutPhoneException withoutPhone(int rowNumber) {
        return new RowWithoutPhoneException("Строка " + rowNumber + ": нет пригодного телефона");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record LeadImportResult(
            int totalRows,
            int added,
            int skippedDuplicates,
            int skippedWithoutPhones,
            int skippedInvalid,
            List<String> errors,
            List<LeadImportManagerAssignment> managerAssignments
    ) {
    }

    public record LeadImportManagerAssignment(
            Long managerId,
            String managerName,
            int added
    ) {
    }

    public record LeadImportOptions(
            List<Long> managerIds,
            Long operatorId,
            Long marketologId
    ) {
        public static LeadImportOptions empty() {
            return new LeadImportOptions(List.of(), null, null);
        }

        private LeadImportOptions normalized() {
            List<Long> normalizedManagerIds = managerIds == null
                    ? List.of()
                    : managerIds.stream()
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .toList();
            return new LeadImportOptions(
                    Collections.unmodifiableList(normalizedManagerIds),
                    operatorId != null && operatorId > 0 ? operatorId : null,
                    marketologId != null && marketologId > 0 ? marketologId : null
            );
        }
    }

    private record ImportedLeadRow(
            int rowNumber,
            String telephoneLead,
            String companyName,
            String phones,
            String mobilePhones,
            String whatsappPhones,
            String emails,
            String websites,
            String vkUrl,
            String telegramUrl,
            String industries,
            String companyType,
            String region,
            String address,
            String cityLead,
            String commentsLead,
            String lidStatus,
            LocalDate createDate,
            LocalDateTime updateStatus,
            LocalDate dateNewTry,
            Long operatorId,
            Long managerId,
            Long marketologId,
            Long telephoneId
    ) {
    }

    private record ImportAssignment(Manager manager, Telephone telephone) {
        private static ImportAssignment empty() {
            return new ImportAssignment(null, null);
        }
    }

    private static final class ImportAssignmentPlan {
        private final List<ManagerPhonePool> pools;
        private int managerCursor;

        private ImportAssignmentPlan(List<ManagerPhonePool> pools) {
            this.pools = pools;
        }

        private static ImportAssignmentPlan empty() {
            return new ImportAssignmentPlan(List.of());
        }

        private ImportAssignment next() {
            if (pools.isEmpty()) {
                return ImportAssignment.empty();
            }

            ManagerPhonePool pool = pools.get(managerCursor);
            managerCursor = (managerCursor + 1) % pools.size();
            return new ImportAssignment(pool.manager(), pool.nextTelephone());
        }
    }

    private static final class ManagerPhonePool {
        private final Manager manager;
        private final List<Telephone> telephones;
        private int telephoneCursor;

        private ManagerPhonePool(Manager manager, List<Telephone> telephones) {
            this.manager = manager;
            this.telephones = List.copyOf(telephones);
        }

        private Manager manager() {
            return manager;
        }

        private Telephone nextTelephone() {
            Telephone telephone = telephones.get(telephoneCursor);
            telephoneCursor = (telephoneCursor + 1) % telephones.size();
            return telephone;
        }
    }

    private static final class ManagerAssignmentCounter {
        private final Manager manager;
        private int count;

        private ManagerAssignmentCounter(Manager manager) {
            this.manager = manager;
        }

        private Manager manager() {
            return manager;
        }

        private int count() {
            return count;
        }

        private void increment() {
            count++;
        }
    }

    private static class RowImportException extends RuntimeException {
        private RowImportException(String message) {
            super(message);
        }
    }

    private static final class RowWithoutPhoneException extends RowImportException {
        private RowWithoutPhoneException(String message) {
            super(message);
        }
    }
}
