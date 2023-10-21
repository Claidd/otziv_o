package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import org.springframework.data.domain.Page;

import java.security.Principal;
import java.util.List;

public interface OrderService {
    OrderDTO newOrderDTO(Long id);
    boolean addNewReview(Long orderId);
    boolean deleteNewReview(Long orderId, Long reviewId);
    boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO);
    boolean changeStatusForOrder(Long orderID, String title);
    OrderDTO getOrderDTO(Long orderId);
    Order getOrder(Long orderId);
    List<OrderDTO> getAllOrderDTO();
    Page<OrderDTOList> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordAndStatus(String keyword, String status, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByWorkerAll(Principal principal, String keyword, int pageNumber, int pageSize);
    Page<OrderDTOList> getAllOrderDTOAndKeywordByWorker(Principal principal, String keyword, String status, int pageNumber, int pageSize);
    void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId);
    boolean changeStatusAndOrderCounter(Long reviewId);

}
