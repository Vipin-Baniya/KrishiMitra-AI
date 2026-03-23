package com.krishimitra.mapper;

import com.krishimitra.dto.LivePriceResponse;
import com.krishimitra.model.entity.MandiPrice;
import org.mapstruct.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MandiPriceMapper {

    @Mapping(target = "mandi",          source = "mandi.name")
    @Mapping(target = "district",       source = "mandi.district")
    @Mapping(target = "state",          source = "mandi.state")
    @Mapping(target = "priceDate",      expression = "java(formatDate(entity.getPriceDate()))")
    @Mapping(target = "trendDirection", expression = "java(computeTrend(entity))")
    @Mapping(target = "changePct",      expression = "java(computeChangePct(entity))")
    LivePriceResponse toDto(MandiPrice entity);

    List<LivePriceResponse> toDtoList(List<MandiPrice> entities);

    default String formatDate(LocalDate date) {
        return date != null ? date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "";
    }

    default String computeTrend(MandiPrice p) {
        if (p.getPrevModalPrice() == null || p.getPrevModalPrice().compareTo(BigDecimal.ZERO) == 0) return "FLAT";
        int cmp = p.getModalPrice().compareTo(p.getPrevModalPrice());
        return cmp > 0 ? "UP" : cmp < 0 ? "DOWN" : "FLAT";
    }

    default BigDecimal computeChangePct(MandiPrice p) {
        if (p.getPrevModalPrice() == null || p.getPrevModalPrice().compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return p.getModalPrice().subtract(p.getPrevModalPrice())
                .divide(p.getPrevModalPrice(), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
