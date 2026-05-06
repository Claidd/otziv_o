package com.hunt.otziv.p_products.mapper;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class OrderDtoMapper {

    public List<OrderDTOList> toBoardDTOList(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyList();
        }

        return orders.stream()
                .map(this::toBoardDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public OrderDTOList toBoardDTO(Order order) {
        if (order == null) {
            return null;
        }

        LocalDate now = LocalDate.now();
        LocalDate changedDate = order.getChanged() != null ? order.getChanged() : now;
        long daysDifference = ChronoUnit.DAYS.between(changedDate, now);

        OrderDetails firstDetail = getFirstDetail(order);

        return OrderDTOList.builder()
                .id(order.getId())
                .companyId(order.getCompany() != null ? order.getCompany().getId() : null)
                .orderDetailsId(firstDetail != null ? firstDetail.getId() : null)
                .companyTitle(order.getCompany() != null ? order.getCompany().getTitle() : "Без компании")
                .companyComments(order.getCompany() != null ? safeString(order.getCompany().getCommentsCompany()) : "")
                .filialTitle(order.getFilial() != null ? safeString(order.getFilial().getTitle()) : "Без филиала")
                .filialUrl(order.getFilial() != null ? safeString(order.getFilial().getUrl()) : "")
                .status(safeStatusTitle(order))
                .sum(order.getSum())
                .companyUrlChat(order.getCompany() != null ? safeString(order.getCompany().getUrlChat()) : "")
                .companyTelephone(order.getCompany() != null ? safeString(order.getCompany().getTelephone()) : "")
                .managerPayText(order.getManager() != null ? safeString(order.getManager().getPayText()) : "")
                .amount(order.getAmount())
                .counter(order.getCounter())
                .waitingForClient(order.isWaitingForClient())
                .workerUserFio(order.getWorker() != null && order.getWorker().getUser() != null
                        ? safeString(order.getWorker().getUser().getFio())
                        : "")
                .categoryTitle(order.getCompany() != null && order.getCompany().getCategoryCompany() != null
                        ? safeString(order.getCompany().getCategoryCompany().getCategoryTitle())
                        : "Не выбрано")
                .subCategoryTitle(order.getCompany() != null && order.getCompany().getSubCategory() != null
                        ? safeString(order.getCompany().getSubCategory().getSubCategoryTitle())
                        : "Не выбрано")
                .created(order.getCreated())
                .changed(order.getChanged())
                .payDay(order.getPayDay())
                .dayToChangeStatusAgo(daysDifference)
                .orderComments(order.getZametka() == null ? "нет заметок" : order.getZametka())
                .build();
    }

    public OrderDTOList toBoardDTO(Object[] row) {
        if (row == null) {
            return null;
        }

        LocalDate now = LocalDate.now();
        LocalDate changedDate = rowLocalDate(row, 19);
        long daysDifference = ChronoUnit.DAYS.between(changedDate != null ? changedDate : now, now);

        return OrderDTOList.builder()
                .id(rowLong(row, 0))
                .companyId(rowLong(row, 1))
                .orderDetailsId(rowUuid(row, 2))
                .companyTitle(rowString(row, 3, "Без компании"))
                .companyComments(rowString(row, 4, ""))
                .filialTitle(rowString(row, 5, "Без филиала"))
                .filialUrl(rowString(row, 6, ""))
                .status(rowString(row, 7, ""))
                .sum(rowBigDecimal(row, 8))
                .companyUrlChat(rowString(row, 9, ""))
                .companyTelephone(rowString(row, 10, ""))
                .managerPayText(rowString(row, 11, ""))
                .amount(rowInteger(row, 12))
                .counter(rowInteger(row, 13))
                .waitingForClient(rowBoolean(row, 14))
                .workerUserFio(rowString(row, 15, ""))
                .categoryTitle(rowString(row, 16, "Не выбрано"))
                .subCategoryTitle(rowString(row, 17, "Не выбрано"))
                .created(rowLocalDate(row, 18))
                .changed(changedDate)
                .payDay(rowLocalDate(row, 20))
                .dayToChangeStatusAgo(daysDifference)
                .orderComments(rowString(row, 21, "нет заметок"))
                .build();
    }

    public List<OrderDTO> toOrderDTOList(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyList();
        }

        return orders.stream()
                .map(this::toOrderDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public OrderDTO toOrderDTO(Order order) {
        if (order == null) {
            return null;
        }

        LocalDate now = LocalDate.now();
        LocalDate changedDate = order.getChanged() != null ? order.getChanged() : now;
        Period period = Period.between(changedDate, now);

        OrderDetails firstDetail = getFirstDetail(order);

        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .sum(order.getSum())
                .created(order.getCreated())
                .changed(order.getChanged())
                .payDay(order.getPayDay())
                .status(order.getStatus() != null ? convertToOrderDTO(order.getStatus()) : null)
                .company(order.getCompany() != null ? convertToCompanyDTO(order.getCompany()) : null)
                .commentsCompany(order.getCompany() != null ? safeString(order.getCompany().getCommentsCompany()) : "")
                .filial(order.getFilial() != null ? convertToFilialDTO(order.getFilial()) : null)
                .manager(order.getManager() != null ? convertToManagerDTO(order.getManager()) : null)
                .worker(order.getWorker() != null ? convertToWorkerDTO(order.getWorker()) : null)
                .details(convertToDetailsDTOList(order.getDetails()))
                .complete(order.isComplete())
                .counter(order.getCounter())
                .dayToChangeStatusAgo(period.getDays())
                .orderDetailsId(firstDetail != null ? firstDetail.getId() : null)
                .orderComments(order.getZametka() == null ? "нет заметок" : order.getZametka())
                .groupId(order.getCompany() != null ? order.getCompany().getGroupId() : null)
                .build();
    }

    public OrderDTO toRepeatOrderDTO(Order order, String status) {
        if (order == null) {
            return null;
        }

        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .worker(order.getWorker() != null ? convertToWorkerDTO(order.getWorker()) : null)
                .manager(order.getManager() != null ? convertToManagerDTO(order.getManager()) : null)
                .company(order.getCompany() != null ? convertToCompanyDTO(order.getCompany()) : null)
                .filial(order.getFilial() != null ? convertToFilialDTO(order.getFilial()) : null)
                .commentsCompany(order.getCompany() != null ? order.getCompany().getCommentsCompany() : "")
                .status(convertToStatusDTO(status))
                .build();
    }

    private CompanyDTO convertToCompanyDTO(Company company) {
        if (company == null) {
            return null;
        }

        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .telephone(company.getTelephone())
                .urlChat(company.getUrlChat())
                .manager(company.getManager() != null ? convertToManagerDTO(company.getManager()) : null)
                .workers(company.getWorkers() != null ? convertToWorkerDTOList(company.getWorkers()) : Collections.emptySet())
                .filials(company.getFilial() != null ? convertToFilialDTOList(company.getFilial()) : Collections.emptySet())
                .categoryCompany(company.getCategoryCompany() != null ? convertToCategoryDto(company.getCategoryCompany()) : null)
                .subCategory(company.getSubCategory() != null ? convertToSubCategoryDto(company.getSubCategory()) : null)
                .groupId(company.getGroupId())
                .build();
    }

    private CategoryDTO convertToCategoryDto(Category category) {
        if (category == null) {
            return null;
        }

        CategoryDTO categoryDTO = new CategoryDTO();
        categoryDTO.setId(category.getId());
        categoryDTO.setCategoryTitle(category.getCategoryTitle());
        return categoryDTO;
    }

    private SubCategoryDTO convertToSubCategoryDto(SubCategory subCategory) {
        if (subCategory == null) {
            return null;
        }

        SubCategoryDTO subCategoryDTO = new SubCategoryDTO();
        subCategoryDTO.setId(subCategory.getId() != null ? subCategory.getId() : 0L);
        subCategoryDTO.setSubCategoryTitle(
                subCategory.getSubCategoryTitle() != null ? subCategory.getSubCategoryTitle() : "Не выбрано"
        );
        return subCategoryDTO;
    }

    private ManagerDTO convertToManagerDTO(Manager manager) {
        if (manager == null) {
            return null;
        }

        return ManagerDTO.builder()
                .managerId(manager.getId())
                .user(manager.getUser())
                .payText(manager.getPayText())
                .clientId(manager.getClientId())
                .build();
    }

    private OrderStatusDTO convertToOrderDTO(OrderStatus orderStatus) {
        if (orderStatus == null) {
            return null;
        }

        return OrderStatusDTO.builder()
                .id(orderStatus.getId())
                .title(orderStatus.getTitle())
                .build();
    }

    private Set<WorkerDTO> convertToWorkerDTOList(Set<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return Collections.emptySet();
        }

        return workers.stream()
                .map(this::convertToWorkerDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private WorkerDTO convertToWorkerDTO(Worker worker) {
        if (worker == null) {
            return null;
        }

        return WorkerDTO.builder()
                .workerId(worker.getId())
                .user(worker.getUser())
                .build();
    }

    private Set<FilialDTO> convertToFilialDTOList(Set<Filial> filials) {
        if (filials == null || filials.isEmpty()) {
            return Collections.emptySet();
        }

        return filials.stream()
                .map(this::convertToFilialDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private FilialDTO convertToFilialDTO(Filial filial) {
        if (filial == null) {
            return null;
        }

        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .url(filial.getUrl())
                .build();
    }

    private List<OrderDetailsDTO> convertToDetailsDTOList(List<OrderDetails> details) {
        if (details == null || details.isEmpty()) {
            return Collections.emptyList();
        }

        return details.stream()
                .map(this::convertToDetailsDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails) {
        if (orderDetails == null) {
            return null;
        }

        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .product(orderDetails.getProduct() != null ? convertToProductDTO(orderDetails.getProduct()) : null)
                .order(orderDetails.getOrder() != null ? convertToNestedOrderDTO(orderDetails.getOrder()) : null)
                .reviews(convertToReviewsDTOList(orderDetails.getReviews()))
                .comment(orderDetails.getComment())
                .build();
    }

    private ProductDTO convertToProductDTO(Product product) {
        if (product == null) {
            return null;
        }

        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    }

    private OrderDTO convertToNestedOrderDTO(Order order) {
        if (order == null) {
            return null;
        }

        return OrderDTO.builder()
                .id(order.getId())
                .amount(order.getAmount())
                .worker(order.getWorker() != null ? convertToWorkerDTO(order.getWorker()) : null)
                .manager(order.getManager() != null ? convertToManagerDTO(order.getManager()) : null)
                .company(order.getCompany() != null ? convertToCompanyDTO(order.getCompany()) : null)
                .groupId(order.getCompany() != null ? order.getCompany().getGroupId() : null)
                .build();
    }

    private OrderStatusDTO convertToStatusDTO(String status) {
        return OrderStatusDTO.builder()
                .title(status)
                .build();
    }

    private List<ReviewDTO> convertToReviewsDTOList(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return Collections.emptyList();
        }

        return reviews.stream()
                .map(this::convertToReviewsDTO)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ReviewDTO convertToReviewsDTO(Review review) {
        if (review == null) {
            return null;
        }

        return ReviewDTO.builder()
                .id(review.getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .build();
    }

    private OrderDetails getFirstDetail(Order order) {
        if (order == null || order.getDetails() == null || order.getDetails().isEmpty()) {
            return null;
        }
        return order.getDetails().get(0);
    }

    private String safeStatusTitle(Order order) {
        if (order == null || order.getStatus() == null || order.getStatus().getTitle() == null) {
            return "";
        }
        return order.getStatus().getTitle();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private Long rowLong(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer rowInteger(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof Number number ? number.intValue() : null;
    }

    private UUID rowUuid(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof UUID uuid ? uuid : null;
    }

    private BigDecimal rowBigDecimal(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof BigDecimal bigDecimal ? bigDecimal : null;
    }

    private boolean rowBoolean(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof Boolean bool && bool;
    }

    private LocalDate rowLocalDate(Object[] row, int index) {
        Object value = rowValue(row, index);
        return value instanceof LocalDate localDate ? localDate : null;
    }

    private String rowString(Object[] row, int index, String fallback) {
        Object value = rowValue(row, index);
        return value == null ? fallback : safeString(value.toString());
    }

    private Object rowValue(Object[] row, int index) {
        return row != null && index >= 0 && index < row.length ? row[index] : null;
    }
}
