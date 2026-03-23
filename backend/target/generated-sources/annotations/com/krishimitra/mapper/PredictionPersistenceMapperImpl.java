package com.krishimitra.mapper;

import com.krishimitra.dto.MlForecastResponse;
import com.krishimitra.model.entity.PricePrediction;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-23T17:02:08+0000",
    comments = "version: 1.5.5.Final, compiler: javac, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PredictionPersistenceMapperImpl implements PredictionPersistenceMapper {

    @Override
    public PricePrediction toEntity(MlForecastResponse mlResp) {
        if ( mlResp == null ) {
            return null;
        }

        PricePrediction.PricePredictionBuilder pricePrediction = PricePrediction.builder();

        pricePrediction.commodity( toJson( mlResp.commodity() ) );
        pricePrediction.sellDecision( toJson( mlResp.sellDecision() ) );
        pricePrediction.waitDays( mlResp.waitDays() );
        pricePrediction.peakDay( mlResp.peakDay() );

        pricePrediction.confidence( mlResp.confidence() != null ? java.math.BigDecimal.valueOf(mlResp.confidence()) : null );
        pricePrediction.pointForecast( toJson(mlResp.pointForecast()) );
        pricePrediction.lower80( toJson(mlResp.lower80()) );
        pricePrediction.upper80( toJson(mlResp.upper80()) );
        pricePrediction.lower95( toJson(mlResp.lower95()) );
        pricePrediction.upper95( toJson(mlResp.upper95()) );
        pricePrediction.expiresAt( java.time.Instant.now().plusSeconds(1800) );

        return pricePrediction.build();
    }
}
