package app.coronawarn.server.common.persistence.domain.validation;

import static app.coronawarn.server.common.persistence.domain.validation.CountryValidator.isValidCountryCode;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidCountryValidator implements ConstraintValidator<ValidCountry, String> {

  @Override
  public boolean isValid(String country, ConstraintValidatorContext constraintValidatorContext) {
    return isValidCountryCode(country);
  }
}
