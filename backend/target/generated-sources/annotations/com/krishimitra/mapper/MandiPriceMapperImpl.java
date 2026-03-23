package com.krishimitra.mapper;

import com.krishimitra.dto.LivePriceResponse;
import com.krishimitra.model.entity.Mandi;
import com.krishimitra.model.entity.MandiPrice;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-23T17:02:08+0000",
    comments = "version: 1.5.5.Final, compiler: javac, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class MandiPriceMapperImpl implements MandiPriceMapper {

    @Override
    public LivePriceResponse toDto(MandiPrice entity) {
        if ( entity == null ) {
            return null;
        }

        String mandi = null;
        String district = null;
        String state = null;
        String commodity = null;
        BigDecimal minPrice = null;
        BigDecimal maxPrice = null;
        BigDecimal modalPrice = null;
        BigDecimal arrivalsQtl = null;

        mandi = entityMandiName( entity );
        district = entityMandiDistrict( entity );
        state = entityMandiState( entity );
        commodity = entity.getCommodity();
        minPrice = entity.getMinPrice();
        maxPrice = entity.getMaxPrice();
        modalPrice = entity.getModalPrice();
        arrivalsQtl = entity.getArrivalsQtl();

        String priceDate = formatDate(entity.getPriceDate());
        String trendDirection = computeTrend(entity);
        BigDecimal changePct = computeChangePct(entity);

        LivePriceResponse livePriceResponse = new LivePriceResponse( commodity, mandi, district, state, minPrice, maxPrice, modalPrice, arrivalsQtl, priceDate, trendDirection, changePct );

        return livePriceResponse;
    }

    @Override
    public List<LivePriceResponse> toDtoList(List<MandiPrice> entities) {
        if ( entities == null ) {
            return null;
        }

        List<LivePriceResponse> list = new ArrayList<LivePriceResponse>( entities.size() );
        for ( MandiPrice mandiPrice : entities ) {
            list.add( toDto( mandiPrice ) );
        }

        return list;
    }

    private String entityMandiName(MandiPrice mandiPrice) {
        if ( mandiPrice == null ) {
            return null;
        }
        Mandi mandi = mandiPrice.getMandi();
        if ( mandi == null ) {
            return null;
        }
        String name = mandi.getName();
        if ( name == null ) {
            return null;
        }
        return name;
    }

    private String entityMandiDistrict(MandiPrice mandiPrice) {
        if ( mandiPrice == null ) {
            return null;
        }
        Mandi mandi = mandiPrice.getMandi();
        if ( mandi == null ) {
            return null;
        }
        String district = mandi.getDistrict();
        if ( district == null ) {
            return null;
        }
        return district;
    }

    private String entityMandiState(MandiPrice mandiPrice) {
        if ( mandiPrice == null ) {
            return null;
        }
        Mandi mandi = mandiPrice.getMandi();
        if ( mandi == null ) {
            return null;
        }
        String state = mandi.getState();
        if ( state == null ) {
            return null;
        }
        return state;
    }
}
