package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Brand;
import cz.kvalitacena.db.repo.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Najde značku podle volného textu, případně založí novou — sdílené mezi
 * {@link ProductCatalogService#create} a {@link CatalogEditService#updateProduct}, ať se
 * nová značka nezakládá dvakrát s jinou logikou tvorby slugu.
 */
@Service
@RequiredArgsConstructor
public class BrandResolutionService {

  private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

  private final BrandRepository brandRepository;

  public Brand resolve(String brandName) {
    String name = blankToNull(brandName);
    if (name == null) return null;
    return brandRepository.findByNameIgnoreCase(name).orElseGet(() -> brandRepository.save(
        Brand.builder().name(name).slug(uniqueSlug(name)).build()));
  }

  private String uniqueSlug(String name) {
    String base = slugify(name);
    String slug = base;
    int suffix = 2;
    while (brandRepository.existsBySlug(slug)) {
      slug = base + "-" + suffix++;
    }
    return slug;
  }

  private String slugify(String value) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", ""); // odstraní diakritická znaménka po rozkladu (NFD)
    String slug = NON_SLUG_CHARS.matcher(normalized.toLowerCase()).replaceAll("-");
    return slug.replaceAll("^-+|-+$", "");
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
