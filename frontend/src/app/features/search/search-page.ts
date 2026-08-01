import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzListModule } from 'ng-zorro-antd/list';
import { Product } from '../../models/catalog';
import { ProductService } from '../../services/product-service';

@Component({
  selector: 'app-search-page',
  imports: [FormsModule, NzInputModule, NzButtonModule, NzListModule, NzIconModule, NzEmptyModule],
  templateUrl: './search-page.html',
  styleUrl: './search-page.css',
})
export class SearchPage {
  private readonly productService = inject(ProductService);
  private readonly router = inject(Router);

  protected readonly query = signal('');
  protected readonly results = signal<Product[]>([]);
  protected readonly loading = signal(false);
  protected readonly searched = signal(false);

  search(): void {
    const q = this.query().trim();
    if (!q) return;

    this.loading.set(true);
    this.productService.search(q).subscribe({
      next: (products) => {
        this.results.set(products);
        this.searched.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.results.set([]);
        this.searched.set(true);
        this.loading.set(false);
      },
    });
  }

  openProduct(product: Product): void {
    this.router.navigate(['/produkt', product.id]);
  }
}
