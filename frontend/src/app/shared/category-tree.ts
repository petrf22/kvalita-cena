import type { NzTreeNodeOptions } from 'ng-zorro-antd/core/tree';

/**
 * Položka číselníku kategorií tak, jak ji vrací dotaz `Categories`
 * (`services/product-service.ts`) — plochý seznam, hierarchii nese `path` (řetězec slugů
 * oddělený „/"), pořadí sourozenců v jedné větvi `sortOrder` (backend `schema.graphqls`,
 * `type Category`).
 */
export interface CategoryOption {
  id: string;
  name: string;
  path: string;
  sortOrder: number;
}

function parentPath(path: string): string | null {
  const idx = path.lastIndexOf('/');
  return idx === -1 ? null : path.slice(0, idx);
}

/**
 * Poskládá plochý seznam kategorií do stromu pro `nz-tree-select` (`features/product-form`).
 * `Query.categories` vrací pořadí podle `path` (abecedně podle slugu), NE podle kurátorského
 * `sortOrder` — appka proto sourozence přeskládá sama, přes `Intl.Collator` podle aktuálního
 * jazyka appky (zvládne `č`/`ř`/`ł` líp než jednotná SQL collation napříč lokalizacemi).
 *
 * Vnitřní uzly (mají potomky) jdou vybrat stejně jako listy — kdo pro své zboží nenajde
 * podkategorii, zvolí nadřazenou místo aby zakládání vzdal (docs/nasazeni.md, kap. 4,
 * "Než pozvat první lidi").
 */
export function buildCategoryTree(
  categories: readonly CategoryOption[],
  locale = 'cs',
): NzTreeNodeOptions[] {
  const collator = new Intl.Collator(locale);
  const byPath = new Map<string, CategoryOption>();
  categories.forEach((category) => byPath.set(category.path, category));

  const childrenByParentPath = new Map<string, CategoryOption[]>();
  const roots: CategoryOption[] = [];
  for (const category of categories) {
    const parent = parentPath(category.path);
    if (parent !== null && byPath.has(parent)) {
      const siblings = childrenByParentPath.get(parent) ?? [];
      siblings.push(category);
      childrenByParentPath.set(parent, siblings);
    } else {
      roots.push(category);
    }
  }

  const sortSiblings = (items: CategoryOption[]): CategoryOption[] =>
    [...items].sort((a, b) => a.sortOrder - b.sortOrder || collator.compare(a.name, b.name));

  const toNode = (category: CategoryOption): NzTreeNodeOptions => {
    const children = sortSiblings(childrenByParentPath.get(category.path) ?? []).map(toNode);
    return {
      key: category.id,
      title: category.name,
      isLeaf: children.length === 0,
      children: children.length > 0 ? children : undefined,
    };
  };

  return sortSiblings(roots).map(toNode);
}

/**
 * Popisek vybrané kategorie pro uzavřené pole výběru ("Potraviny › Mléčné výrobky › Máslo") —
 * samotný list ("Máslo") by bez zbytku větve nebylo poznat, pod čím v číselníku leží.
 */
export function categoryBreadcrumb(
  categoryId: string,
  categories: readonly CategoryOption[],
): string {
  const byId = new Map(categories.map((category) => [category.id, category]));
  const byPath = new Map(categories.map((category) => [category.path, category]));
  const category = byId.get(categoryId);
  if (!category) return '';

  const chain: string[] = [];
  let current: CategoryOption | undefined = category;
  while (current) {
    chain.unshift(current.name);
    const parent = parentPath(current.path);
    current = parent !== null ? byPath.get(parent) : undefined;
  }
  return chain.join(' › ');
}
