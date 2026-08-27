package cz.kvalitacena.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Kurátorovaná, deterministická mapa OFF taxonomie na podstatně menší interní strom. */
@Component
public class OffCategoryMapper {

  private final Map<String, Mapping> mappings;

  public OffCategoryMapper() {
    this.mappings = load();
  }

  public String categorySlugFor(List<String> offTags) {
    if (offTags == null) return null;
    return offTags.stream().map(mappings::get).filter(java.util.Objects::nonNull)
        .max(Comparator.comparingInt(Mapping::priority)).map(Mapping::slug).orElse(null);
  }

  private Map<String, Mapping> load() {
    Map<String, Mapping> result = new HashMap<>();
    try (var reader = new BufferedReader(new InputStreamReader(
        new ClassPathResource("off-category-map.csv").getInputStream(), StandardCharsets.UTF_8))) {
      reader.lines().skip(1).filter(line -> !line.isBlank() && !line.startsWith("#")).forEach(line -> {
        String[] values = line.split(",", -1);
        if (values.length != 3) throw new IllegalStateException("Invalid off-category-map.csv row: " + line);
        result.put(values[0], new Mapping(values[1], Integer.parseInt(values[2])));
      });
      return Map.copyOf(result);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load off-category-map.csv", e);
    }
  }

  private record Mapping(String slug, int priority) {
  }
}
