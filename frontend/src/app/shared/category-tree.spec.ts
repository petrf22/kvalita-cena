import { describe, expect, it } from 'vitest';
import { buildCategoryTree, categoryBreadcrumb, type CategoryOption } from './category-tree';

const CATEGORIES: CategoryOption[] = [
  { id: '1', name: 'Potraviny', path: 'potraviny', sortOrder: 10 },
  { id: '2', name: 'Nápoje', path: 'napoje', sortOrder: 20 },
  { id: '3', name: 'Mléčné výrobky', path: 'potraviny/mlecne', sortOrder: 10 },
  { id: '4', name: 'Pečivo', path: 'potraviny/pecivo', sortOrder: 20 },
  { id: '5', name: 'Máslo', path: 'potraviny/mlecne/maslo', sortOrder: 10 },
  { id: '6', name: 'Sýry', path: 'potraviny/mlecne/syry', sortOrder: 20 },
];

describe('buildCategoryTree', () => {
  it('vrátí kořeny seřazené podle sortOrder, ne abecedně podle path', () => {
    const tree = buildCategoryTree(CATEGORIES);
    expect(tree.map((n) => n.title)).toEqual(['Potraviny', 'Nápoje']);
  });

  it('vnoří potomky pod rodiče podle path a seřadí je podle sortOrder', () => {
    const tree = buildCategoryTree(CATEGORIES);
    const potraviny = tree.find((n) => n.title === 'Potraviny')!;
    expect(potraviny.children?.map((n) => n.title)).toEqual(['Mléčné výrobky', 'Pečivo']);

    const mlecne = potraviny.children!.find((n) => n.title === 'Mléčné výrobky')!;
    expect(mlecne.children?.map((n) => n.title)).toEqual(['Máslo', 'Sýry']);
  });

  it('označí uzly bez potomků jako listy a vnitřní uzly ne', () => {
    const tree = buildCategoryTree(CATEGORIES);
    const potraviny = tree.find((n) => n.title === 'Potraviny')!;
    const napoje = tree.find((n) => n.title === 'Nápoje')!;
    expect(potraviny.isLeaf).toBe(false);
    expect(napoje.isLeaf).toBe(true);

    const maslo = potraviny
      .children!.find((n) => n.title === 'Mléčné výrobky')!
      .children!.find((n) => n.title === 'Máslo')!;
    expect(maslo.isLeaf).toBe(true);
  });

  it('řadí sourozence podle jména přes Intl.Collator, ne přes výchozí string compare', () => {
    const categories: CategoryOption[] = [
      { id: '1', name: 'Žvýkačky', path: 'a', sortOrder: 10 },
      { id: '2', name: 'Čokoláda', path: 'b', sortOrder: 10 },
      { id: '3', name: 'Ananas', path: 'c', sortOrder: 10 },
    ];
    const tree = buildCategoryTree(categories, 'cs');
    expect(tree.map((n) => n.title)).toEqual(['Ananas', 'Čokoláda', 'Žvýkačky']);
  });
});

describe('categoryBreadcrumb', () => {
  it('poskládá celou větev od kořene k vybrané kategorii', () => {
    expect(categoryBreadcrumb('5', CATEGORIES)).toBe('Potraviny › Mléčné výrobky › Máslo');
  });

  it('u kořenové kategorie vrátí jen její vlastní jméno', () => {
    expect(categoryBreadcrumb('1', CATEGORIES)).toBe('Potraviny');
  });

  it('u neznámého id vrátí prázdný řetězec', () => {
    expect(categoryBreadcrumb('nope', CATEGORIES)).toBe('');
  });
});
