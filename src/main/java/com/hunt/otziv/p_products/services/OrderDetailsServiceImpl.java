package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
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
    public OrderDetails save(OrderDetails orderDetails) { // Сохранить детали заказ в БД
        return orderDetailsRepository.save(orderDetails);
    } // Сохранить детали заказ в БД

    public OrderDetails getOrderDetailById(UUID orderDetailId){ // Взять детали по Id
        return orderDetailsRepository.findById(orderDetailId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderDetailId)));
    } // Взять детали по Id

    @Override
    public void deleteOrderDetailsById(UUID orderDetailId) {
        orderDetailsRepository.deleteById(orderDetailId);
    }

    @Override
    public void deleteOrderDetails(OrderDetails orderDetails) {
        orderDetailsRepository.delete(orderDetails);
    }



    public OrderDetailsDTO getOrderDetailDTOById(UUID orderDetailId){ // Взять детали дто по Id
        return convertToDetailsDTO(orderDetailsRepository.findById(orderDetailId).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%d' не найден", orderDetailId))));
    } // Взять детали дто по Id

    private OrderDetailsDTO convertToDetailsDTO(OrderDetails orderDetails){ // перевод деталей в дто
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
    } // перевод деталей в дто
    private ProductDTO convertToProductDTO(Product product){ // перевод продукта в дто
        return ProductDTO.builder()
                .id(product.getId())
                .title(product.getTitle())
                .price(product.getPrice())
                .build();
    } // перевод продукта в дто
    private OrderDTO convertToOrderDTO(Order order){ // перевод заказа в дто
        return OrderDTO.builder()
                .id(order.getId())
                .company(convertToCompanyDTO(order.getCompany()))
                .amount(order.getAmount())
                .counter(order.getCounter())
                .orderDetailsId(order.getDetails().iterator().next().getId())
                .filial(convertToFilialDTO(order.getFilial()))
                .build();
    } // перевод заказа в дто

    private CompanyDTO convertToCompanyDTO(Company company){ // перевод компании в дто
        return CompanyDTO.builder()
                .id(company.getId())
                .title(company.getTitle())
                .urlChat(company.getUrlChat())
                .build();
    } // перевод компании в дто
    private FilialDTO convertToFilialDTO(Filial filial){ // перевод компании в дто
        return FilialDTO.builder()
                .id(filial.getId())
                .title(filial.getTitle())
                .build();
    } // перевод компании в дто
    private List<ReviewDTO> convertToReviewsDTOList(List<Review> reviews){ // перевод отзыва в дто
        return reviews.stream().map(this::convertToReviewsDTO).collect(Collectors.toList());
    } // перевод отзыва в дто
    private ReviewDTO convertToReviewsDTO(Review review){ // перевод отзыва в дто
        return ReviewDTO.builder()
                .id(review.getId())
                .text(review.getText())
                .answer(review.getAnswer())
                .orderDetailsId(review.getOrderDetails().getId())
                .publish(review.isPublish())
                .publishedDate(review.getPublishedDate())
                .build();
    } // перевод отзыва в дто
}
