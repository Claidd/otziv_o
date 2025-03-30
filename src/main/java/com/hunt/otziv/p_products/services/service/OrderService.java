package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.util.Pair;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface OrderService {
    OrderDTO newOrderDTO(Long id);
    boolean addNewReview(Long orderId);
    boolean deleteNewReview(Long orderId, Long reviewId);
    boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO);
    boolean changeStatusForOrder(Long orderID, String title) throws Exception;
    OrderDTO getOrderDTO(Long orderId);
    Order getOrder(Long orderId);
    List<OrderDTO> getAllOrderDTO();
    Page<OrderDTOList> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordAndStatus(String keyword, String status, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByWorkerAll(Principal principal, String keyword, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByWorker(Principal principal, String keyword, String status, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByOwnerAll(Principal principal, String keyword, int pageNumber, int pageSize);
    Page<OrderDTOList>  getAllOrderDTOAndKeywordByOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize);

    void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId);
    boolean changeStatusAndOrderCounter(Long reviewId) throws Exception;
    Page<OrderDTOList> getAllOrderDTOCompanyIdAndKeyword(Long companyId, String keyword, int pageNumber, int pageSize);

    boolean deleteOrder(Long orderId, Principal principal);

    int getAllOrderDTOByStatus(String status);

    int getAllOrderDTOByStatusToManager(Manager manager, String status);

    int getAllOrderDTOByStatusToOwner(Set<Manager> managerList, String status);


    Company checkStatusToCompany(Company company);

    OrderDTO convertToOrderDTOToRepeat(Order order);

    void updateOrderToWorker(OrderDTO orderDTO, Long companyId, Long orderId);

    int countOrdersByWorkerAndStatus(Worker worker, String status);


    Map<String, Pair<Long, Long>> getNewOrderAll(String statusNew, String statusCorrect);

    Map<String, Long> getAllOrdersToMonth(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);
}
