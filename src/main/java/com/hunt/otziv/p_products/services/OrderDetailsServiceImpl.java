package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderDetailsServiceImpl implements OrderDetailsService {

    private final OrderDetailsRepository orderDetailsRepository;
    @Override
    public OrderDetails save(OrderDetails orderDetails) {
        return orderDetailsRepository.save(orderDetails);
    }

    public OrderDetails getOrderDetailById(UUID orderDetailId){
        return orderDetailsRepository.findById(orderDetailId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderDetailId)));
    }
    public OrderDetailsDTO getOrderDetailDTOById(UUID orderDetailId){
        return convertToDetailsDTO(orderDetailsRepository.findById(orderDetailId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderDetailId))));
    }

    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails){
        return OrderDetailsDTO.builder()
                .id(orderDetails.getId())
                .amount(orderDetails.getAmount())
                .price(orderDetails.getPrice())
                .publishedDate(orderDetails.getPublishedDate())
                .product(convertToProductDTO(orderDetails.getProduct()))
                .order(convertToOrderDTO(orderDetails.getOrder()))
                .reviews(convertToReviewsDTOList(orderDetails.getReviews()))
                .comment(orderDetails.getComment())
                .build();
    }
    private ProductDTO convertToProductDTO(Product product){
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    }
    private OrderDTO convertToOrderDTO(Order order){
        return OrderDTO.builder()
                .id(order.getId())
                .company(convertToCompanyDTO(order.getCompany()))
                .amount(order.getAmount())
                .counter(order.getCounter())
                .orderDetailsId(order.getDetails().iterator().next().getId())
                .build();
    }

    private CompanyDTO convertToCompanyDTO(Company company){
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .urlChat(company.getUrlChat())
                .build();
    }
    private List<ReviewDTO> convertToReviewsDTOList(List<Review> reviews){
        return reviews.stream().map(this::convertToReviewsDTO).collect(Collectors.toList());
    }
    private ReviewDTO convertToReviewsDTO(Review review){
        return ReviewDTO.builder()
                .id(review.getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .orderDetailsId(review.getOrderDetails().getId())
                .publish(review.isPublish())
                .publishedDate(review.getPublishedDate())
                .build();
    }
}
