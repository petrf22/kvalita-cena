package cz.kvalitacena.config;

import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Vlastní scalary místo graphql-java-extended-scalars — ta zatím nemá release kompatibilní
 * s graphql-java 25 (transitivní závislost přes spring-boot-graphql 4.0.0), viz build.gradle.
 * Tři scalary jsou dost triviální, aby se nevyplatilo řešit verzovou nekompatibilitu.
 */
final class GraphQlScalars {

  private GraphQlScalars() {
  }

  static GraphQLScalarType longScalar() {
    return GraphQLScalarType.newScalar()
        .name("Long")
        .description("64bitové celé číslo")
        .coercing(new Coercing<Long, Long>() {
          @Override
          public Long serialize(Object value) {
            if (value instanceof Number number) return number.longValue();
            throw new CoercingSerializeException("Očekáváno číslo, přišlo: " + value);
          }

          @Override
          public Long parseValue(Object input) {
            if (input instanceof Number number) return number.longValue();
            if (input instanceof String s) {
              try {
                return Long.parseLong(s);
              } catch (NumberFormatException e) {
                throw new CoercingParseValueException("Neplatné celé číslo: " + s, e);
              }
            }
            throw new CoercingParseValueException("Očekáváno číslo, přišlo: " + input);
          }

          @Override
          public Long parseLiteral(Object input) {
            if (input instanceof IntValue intValue) return intValue.getValue().longValue();
            throw new CoercingParseLiteralException("Očekáváno celé číslo, přišlo: " + input);
          }
        })
        .build();
  }

  static GraphQLScalarType bigDecimalScalar() {
    return GraphQLScalarType.newScalar()
        .name("BigDecimal")
        .description("Desetinné číslo s přesností bez zaokrouhlovacích chyb (ceny)")
        .coercing(new Coercing<BigDecimal, BigDecimal>() {
          @Override
          public BigDecimal serialize(Object value) {
            if (value instanceof BigDecimal bd) return bd;
            if (value instanceof Number number) return new BigDecimal(number.toString());
            throw new CoercingSerializeException("Očekáváno číslo, přišlo: " + value);
          }

          @Override
          public BigDecimal parseValue(Object input) {
            try {
              if (input instanceof BigDecimal bd) return bd;
              if (input instanceof Number number) return new BigDecimal(number.toString());
              if (input instanceof String s) return new BigDecimal(s);
            } catch (NumberFormatException e) {
              throw new CoercingParseValueException("Neplatné desetinné číslo: " + input, e);
            }
            throw new CoercingParseValueException("Očekáváno číslo, přišlo: " + input);
          }

          @Override
          public BigDecimal parseLiteral(Object input) {
            if (input instanceof IntValue intValue) return new BigDecimal(intValue.getValue());
            if (input instanceof FloatValue floatValue) return floatValue.getValue();
            if (input instanceof StringValue stringValue) {
              try {
                return new BigDecimal(stringValue.getValue());
              } catch (NumberFormatException e) {
                throw new CoercingParseLiteralException("Neplatné desetinné číslo: " + input, e);
              }
            }
            throw new CoercingParseLiteralException("Očekáváno číslo, přišlo: " + input);
          }
        })
        .build();
  }

  static GraphQLScalarType dateTimeScalar() {
    return GraphQLScalarType.newScalar()
        .name("DateTime")
        .description("Datum a čas v ISO-8601 s časovým pásmem (RFC 3339), např. 2026-07-26T14:30:00+02:00")
        .coercing(new Coercing<OffsetDateTime, String>() {
          @Override
          public String serialize(Object value) {
            if (value instanceof OffsetDateTime odt) return odt.toString();
            throw new CoercingSerializeException("Očekáváno OffsetDateTime, přišlo: " + value);
          }

          @Override
          public OffsetDateTime parseValue(Object input) {
            if (input instanceof OffsetDateTime odt) return odt;
            if (input instanceof String s) return parse(s, CoercingParseValueException::new);
            throw new CoercingParseValueException("Očekáván ISO-8601 řetězec, přišlo: " + input);
          }

          @Override
          public OffsetDateTime parseLiteral(Object input) {
            if (input instanceof StringValue stringValue) {
              return parse(stringValue.getValue(), CoercingParseLiteralException::new);
            }
            throw new CoercingParseLiteralException("Očekáván ISO-8601 řetězec, přišlo: " + input);
          }

          private OffsetDateTime parse(String s, java.util.function.BiFunction<String, Throwable, RuntimeException> onError) {
            try {
              return OffsetDateTime.parse(s);
            } catch (DateTimeParseException e) {
              throw onError.apply("Neplatné datum a čas (očekáván ISO-8601 s časovým pásmem): " + s, e);
            }
          }
        })
        .build();
  }
}
