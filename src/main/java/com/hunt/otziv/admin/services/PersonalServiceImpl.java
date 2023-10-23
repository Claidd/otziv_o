package com.hunt.otziv.admin.services;

import com.hunt.otziv.admin.dto.personal_stat.UserStatDTO;
import com.hunt.otziv.admin.dto.presonal.ManagersListDTO;
import com.hunt.otziv.admin.dto.presonal.MarketologsListDTO;
import com.hunt.otziv.admin.dto.presonal.OperatorsListDTO;
import com.hunt.otziv.admin.dto.presonal.WorkersListDTO;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.*;
import com.hunt.otziv.u_users.services.service.*;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonalServiceImpl implements PersonalService {
    private final ManagerService managerService;
    private final MarketologService marketologService;
    private final WorkerService workerService;
    private final OperatorService operatorService;
    private final ReviewService reviewService;
    private final ZpService zpService;
    private final UserService userService;





//    ========================================== PERSONAL STAT START ==================================================
    public UserStatDTO gerWorkerReviews(String login){
        LocalDate localDate = LocalDate.now();
        List<Zp> zps = zpService.getAllWorkerZp(login);
        List<Zp> zpPay1Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(1))).toList();
        List<Zp> zpPay7Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(7))).toList();
        List<Zp> zpPay30Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(30))).toList();
        List<Zp> zpPay365Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(365))).toList();
        List<Zp> zpPay2Day = zps.stream().filter(z -> z.getCreated().isEqual(localDate.minusDays(2))).toList();
        List<Zp> zpPay14Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(14)) && z.getCreated().isBefore(localDate.minusDays(7))).toList();
        List<Zp> zpPay60Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(60)) && z.getCreated().isBefore(localDate.minusDays(30))).toList();
        List<Zp> zpPay730Day = zps.stream().filter(z -> z.getCreated().isAfter(localDate.minusDays(730)) && z.getCreated().isBefore(localDate.minusDays(365))).toList();

        BigDecimal sum1 = zpPay1Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum7 = zpPay7Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum30 = zpPay30Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма
        BigDecimal sum365 = zpPay365Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add); // первая сумма

        BigDecimal sum2 = zpPay2Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);; // вторая сумма
        BigDecimal sum14 = zpPay14Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);; // вторая сумма
        BigDecimal sum60 = zpPay60Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);; // вторая сумма
        BigDecimal sum730 = zpPay730Day.stream().map(Zp::getSum).reduce(BigDecimal.ZERO, BigDecimal::add);; // вторая сумма

        User user = userService.findByUserName(login).orElseThrow();
        Long imageId = user.getImage() != null ? user.getImage().getId() : 1L;
        UserStatDTO userStatDTO = new UserStatDTO();
        userStatDTO.setId(user.getId());
        userStatDTO.setImageId(imageId);
        userStatDTO.setFio(user.getFio());
        userStatDTO.setCoefficient(user.getCoefficient());
        userStatDTO.setSum1Day(sum1.intValue());
        userStatDTO.setSum1Week(sum7.intValue());
        userStatDTO.setSum1Month(sum30.intValue());
        userStatDTO.setSum1Year(sum365.intValue());
        userStatDTO.setSumOrders1Month(zpPay30Day.size());
        userStatDTO.setSumOrders1Year(zpPay365Day.size());
        userStatDTO.setPercent1Day(percentageComparison(sum1,sum2).intValue());
        userStatDTO.setPercent1Week(percentageComparison(sum7,sum14).intValue());
        userStatDTO.setPercent1Month(percentageComparison(sum30,sum60).intValue());
        userStatDTO.setPercent1Year(percentageComparison(sum365,sum730).intValue());


        System.out.println(userStatDTO);
        return userStatDTO;
}

    private BigDecimal percentageComparison(BigDecimal sum1, BigDecimal sum2) {

        if (sum1.compareTo(BigDecimal.ZERO) <= 0 && sum2.compareTo(BigDecimal.ZERO) > 0) {
            // Обработка случая, когда sum1 равно нулю
            return sum2.divide(sum2, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).negate();
        }
        if (sum1.compareTo(BigDecimal.ZERO) > 0 && sum2.compareTo(BigDecimal.ZERO) <= 0) {
            // Обработка случая, когда sum2 равно нулю
            return sum1.divide(sum1, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
        if (sum1.compareTo(BigDecimal.ZERO) == 0 && sum2.compareTo(BigDecimal.ZERO) == 0) {
            // Обработка случая, когда sum2 равно нулю
            return BigDecimal.ZERO;
        }
        else {
            BigDecimal difference = sum1.subtract(sum2); // разница между суммами
            if (difference.compareTo(BigDecimal.ZERO) > 0){
                return difference.divide(sum1, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            }
            if (difference.compareTo(BigDecimal.ZERO) < 0){
                return difference.divide(sum2, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            }
            else return BigDecimal.ZERO;
        }
    }
    private BigDecimal getPrice(Product product){
        return product.getPrice();
    }




//    ========================================== PERSONAL STAT FINISH ==================================================












//    ========================================== PERSONAL LIST START ==================================================
    public List<ManagersListDTO> getManagers(){
        return managerService.getAllManagers().stream().map(this::toManagersListDTO).collect(Collectors.toList());
    }
    public List<MarketologsListDTO> getMarketologs(){
        return marketologService.getAllMarketologs().stream().map(this::toMarketologsListDTO).collect(Collectors.toList());
    }
    public List<WorkersListDTO> gerWorkers(){
        return workerService.getAllWorkers().stream().map(this::toWorkersListDTO).collect(Collectors.toList());
    }
    public List<OperatorsListDTO> gerOperators(){
        return operatorService.getAllOperators().stream().map(this::toOperatorsListDTO).collect(Collectors.toList());
    }

    private ManagersListDTO toManagersListDTO(Manager manager){
        Long imageId = manager.getUser().getImage() != null ? manager.getUser().getImage().getId() : 1L;
        return ManagersListDTO.builder()
                .id(manager.getId())
                .userId(manager.getUser().getId())
                .fio(manager.getUser().getFio())
                .login(manager.getUser().getUsername())
                .imageId(imageId)
                .build();
    }

    private MarketologsListDTO toMarketologsListDTO(Marketolog marketolog){
        Long imageId = marketolog.getUser().getImage() != null ? marketolog.getUser().getImage().getId() : 1L;
        return MarketologsListDTO.builder()
                .id(marketolog.getId())
                .userId(marketolog.getUser().getId())
                .fio(marketolog.getUser().getFio())
                .login(marketolog.getUser().getUsername())
                .imageId(imageId)
                .build();
    }

    private WorkersListDTO toWorkersListDTO(Worker worker){
        Long imageId = worker.getUser().getImage() != null ? worker.getUser().getImage().getId() : 1L;
        return WorkersListDTO.builder()
                .id(worker.getId())
                .userId(worker.getUser().getId())
                .fio(worker.getUser().getFio())
                .login(worker.getUser().getUsername())
                .imageId(imageId)
                .build();
    }

    private OperatorsListDTO toOperatorsListDTO(Operator operator){
        Long imageId = operator.getUser().getImage() != null ? operator.getUser().getImage().getId() : 1L;
        return OperatorsListDTO.builder()
                .id(operator.getId())
                .userId(operator.getUser().getId())
                .fio(operator.getUser().getFio())
                .login(operator.getUser().getUsername())
                .imageId(imageId)
                .build();
    }

//    ========================================== PERSONAL LIST FINISH ==================================================
}
