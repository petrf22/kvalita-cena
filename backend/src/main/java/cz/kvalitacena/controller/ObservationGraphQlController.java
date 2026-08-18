package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.ObservationSource;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.service.PriceObservationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ObservationGraphQlController {

  private final PriceObservationService priceObservationService;
  private final HttpServletRequest servletRequest;

  @MutationMapping
  public List<PriceObservation> submitObservations(@Argument SubmitObservationsInput input,
      Authentication authentication) {
    UUID publicUid = authentication != null && authentication.getPrincipal() instanceof UUID uid
        ? uid
        : null;
    return priceObservationService.submit(input, publicUid, resolveSource());
  }

  private ObservationSource resolveSource() {
    String clientKind = servletRequest.getHeader("X-Client-Kind");
    return "ANDROID".equalsIgnoreCase(clientKind) ? ObservationSource.MOBILE : ObservationSource.WEB;
  }
}
