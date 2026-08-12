import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoService, provideTranslocoScope } from '@jsverse/transloco';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzAvatarModule } from 'ng-zorro-antd/avatar';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzListModule } from 'ng-zorro-antd/list';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { Audience, Profile, ProfileField, ProfileFieldAudience, ProfileVisibility } from '../../models/auth';
import { UpdateProfileInput } from '../../models/generated/graphql';
import { translateError } from '../../shared/error-message';
import { ALLOWED_PHOTO_TYPES, MAX_PHOTO_BYTES } from '../../shared/photo-upload-validation';
import { AuthService } from '../../services/auth-service';
import { MediaService } from '../../services/media-service';
import { ViewerService } from '../../services/viewer-service';

/** Pořadí řádků v tabulce viditelnosti — nezávislé na pořadí generovaného enumu. */
const VISIBILITY_FIELDS: readonly ProfileField[] = [
  ProfileField.FirstName,
  ProfileField.LastName,
  ProfileField.DisplayName,
  ProfileField.Phone,
  ProfileField.ContactEmail,
  ProfileField.Avatar,
];

/**
 * Editace profilu přihlášeného uživatele — jméno, příjmení, přezdívka, telefon, kontaktní
 * e-mail (nepovinné, šifrované), avatar, viditelnost (docs/soukromi.md, "Profil uživatele a
 * viditelnost"). Výchozí viditelnost je ANONYMOUS; u PUBLIC/FRIENDS si uživatel po jednotlivých
 * polích vybere, co uvidí veřejnost a co jen (zatím neimplementovaní) přátelé.
 *
 * Přihlašovací e-mail je needitovatelný přímo tady — mění se přes samostatný OTP tok
 * (`AuthService.requestEmailChange`/`confirmEmailChange`), aby si uživatel překlepem nezamkl
 * účet. Avatar jde přes REST multipart (`MediaService.uploadAvatar`), zbytek přes GraphQL
 * (`ViewerService.updateProfile`/`deleteAvatar`) — stejné rozdělení jako u fotek zboží/obchodu.
 */
@Component({
  selector: 'app-profile-page',
  imports: [
    FormsModule,
    RouterLink,
    NzAlertModule,
    NzAvatarModule,
    NzButtonModule,
    NzCardModule,
    NzCheckboxModule,
    NzFormModule,
    NzIconModule,
    NzInputModule,
    NzListModule,
    NzModalModule,
    NzRadioModule,
    NzTagModule,
    NzTooltipModule,
    TranslocoDirective,
  ],
  providers: [provideTranslocoScope('profile')],
  templateUrl: './profile-page.html',
  styleUrl: './profile-page.css',
})
export class ProfilePage {
  private readonly viewerService = inject(ViewerService);
  private readonly mediaService = inject(MediaService);
  protected readonly auth = inject(AuthService);
  private readonly transloco = inject(TranslocoService);

  protected readonly ProfileVisibility = ProfileVisibility;
  protected readonly ProfileField = ProfileField;
  protected readonly visibilityFields = VISIBILITY_FIELDS;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly firstName = signal('');
  protected readonly lastName = signal('');
  protected readonly displayName = signal('');
  protected readonly phone = signal('');
  protected readonly contactEmail = signal('');
  protected readonly loginEmail = signal('');
  protected readonly visibility = signal<ProfileVisibility>(ProfileVisibility.Anonymous);
  protected readonly avatar = signal<Profile['avatar']>(null);

  private readonly publicFields = signal<ReadonlySet<ProfileField>>(new Set());
  private readonly friendsFields = signal<ReadonlySet<ProfileField>>(new Set());

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly saveMessage = signal<string | null>(null);

  protected readonly avatarUploading = signal(false);
  protected readonly avatarError = signal<string | null>(null);

  // --- Změna přihlašovacího e-mailu (samostatný modal/tok) ---
  protected readonly emailChangeVisible = signal(false);
  protected readonly emailChangeStep = signal<'email' | 'code'>('email');
  protected readonly newEmail = signal('');
  protected readonly emailChangeCode = signal('');
  protected readonly emailChangeChallengeUid = signal<string | null>(null);
  protected readonly emailChangeLoading = signal(false);
  protected readonly emailChangeError = signal<string | null>(null);
  protected readonly emailChangeSuccess = signal(false);

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.viewerService.me().subscribe({
      next: (viewer) => {
        this.loading.set(false);
        if (!viewer) {
          this.loadError.set(this.transloco.translate('profile.loadFailed'));
          return;
        }
        this.applyProfile(viewer.profile);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(this.transloco.translate('profile.loadFailed'));
      },
    });
  }

  private applyProfile(profile: Profile): void {
    this.firstName.set(profile.firstName ?? '');
    this.lastName.set(profile.lastName ?? '');
    this.phone.set(profile.phone ?? '');
    this.contactEmail.set(profile.contactEmail ?? '');
    this.loginEmail.set(profile.loginEmail);
    this.visibility.set(profile.visibility);
    this.avatar.set(profile.avatar);
    this.publicFields.set(this.fieldsFor(profile.visibleFields, Audience.Public));
    this.friendsFields.set(this.fieldsFor(profile.visibleFields, Audience.Friends));
  }

  private fieldsFor(entries: readonly ProfileFieldAudience[], audience: Audience): ReadonlySet<ProfileField> {
    return new Set(entries.filter((e) => e.audience === audience).map((e) => e.field));
  }

  protected isChecked(field: ProfileField, audience: Audience): boolean {
    return audience === Audience.Public ? this.publicFields().has(field) : this.friendsFields().has(field);
  }

  protected toggleField(field: ProfileField, audience: Audience): void {
    const target = audience === Audience.Public ? this.publicFields : this.friendsFields;
    const next = new Set(target());
    if (next.has(field)) next.delete(field);
    else next.add(field);
    target.set(next);
  }

  protected fieldLabel(field: ProfileField): string {
    return this.transloco.translate(`profile.field.${field}`);
  }

  save(): void {
    this.saving.set(true);
    this.saveError.set(null);
    this.saveMessage.set(null);

    const visibleFields: ProfileFieldAudience[] = [
      ...[...this.publicFields()].map((field) => ({ field, audience: Audience.Public })),
      ...[...this.friendsFields()].map((field) => ({ field, audience: Audience.Friends })),
    ];
    const input: UpdateProfileInput = {
      firstName: this.firstName().trim() || null,
      clearFirstName: this.firstName().trim() === '',
      lastName: this.lastName().trim() || null,
      clearLastName: this.lastName().trim() === '',
      displayName: this.displayName().trim() || null,
      clearDisplayName: this.displayName().trim() === '',
      phone: this.phone().trim() || null,
      clearPhone: this.phone().trim() === '',
      contactEmail: this.contactEmail().trim() || null,
      clearContactEmail: this.contactEmail().trim() === '',
      visibility: this.visibility(),
      visibleFields,
    };

    this.viewerService.updateProfile(input).subscribe({
      next: (viewer) => {
        this.saving.set(false);
        this.applyProfile(viewer.profile);
        this.saveMessage.set(this.transloco.translate('profile.save.success'));
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(translateError(err, this.transloco));
      },
    });
  }

  onAvatarSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';
    if (!file) return;

    this.avatarError.set(null);
    if (!ALLOWED_PHOTO_TYPES.includes(file.type)) {
      this.avatarError.set(this.transloco.translate('errors.PHOTO_UNSUPPORTED_FORMAT'));
      return;
    }
    if (file.size > MAX_PHOTO_BYTES) {
      this.avatarError.set(this.transloco.translate('errors.PHOTO_TOO_LARGE'));
      return;
    }

    this.avatarUploading.set(true);
    this.mediaService.uploadAvatar(file).subscribe({
      next: (photo) => {
        this.avatarUploading.set(false);
        this.avatar.set(photo);
      },
      // REST upload, ne GraphQL — chyba nenese extensions.code (stejný důvod jako photo-gallery.ts).
      error: () => {
        this.avatarUploading.set(false);
        this.avatarError.set(this.transloco.translate('profile.avatar.uploadFailed'));
      },
    });
  }

  removeAvatar(): void {
    this.avatarError.set(null);
    this.viewerService.deleteAvatar().subscribe({
      next: (viewer) => this.avatar.set(viewer.profile.avatar),
      error: (err) => this.avatarError.set(translateError(err, this.transloco)),
    });
  }

  // --- Změna přihlašovacího e-mailu ---

  openEmailChangeModal(): void {
    this.newEmail.set('');
    this.emailChangeCode.set('');
    this.emailChangeChallengeUid.set(null);
    this.emailChangeStep.set('email');
    this.emailChangeError.set(null);
    this.emailChangeSuccess.set(false);
    this.emailChangeVisible.set(true);
  }

  closeEmailChangeModal(): void {
    this.emailChangeVisible.set(false);
  }

  requestEmailChangeCode(): void {
    const email = this.newEmail().trim();
    if (!email) return;

    this.emailChangeLoading.set(true);
    this.emailChangeError.set(null);
    this.auth.requestEmailChange(email).subscribe({
      next: (response) => {
        this.emailChangeLoading.set(false);
        this.emailChangeChallengeUid.set(response.challengeUid);
        this.emailChangeStep.set('code');
      },
      error: (err) => {
        this.emailChangeLoading.set(false);
        this.emailChangeError.set(translateError(err, this.transloco));
      },
    });
  }

  confirmEmailChangeCode(): void {
    const challengeUid = this.emailChangeChallengeUid();
    const email = this.newEmail().trim();
    const code = this.emailChangeCode().trim();
    if (!challengeUid || !code) return;

    this.emailChangeLoading.set(true);
    this.emailChangeError.set(null);
    this.auth.confirmEmailChange(challengeUid, code, email).subscribe({
      next: () => {
        // token_version se na serveru inkrementoval (odhlásí ostatní zařízení) — obnovíme
        // access token hned, ať se aktuální relace nemusí spoléhat na refresh až po chybě.
        this.auth.refresh().subscribe({
          next: () => {
            this.emailChangeLoading.set(false);
            this.emailChangeSuccess.set(true);
            this.loginEmail.set(email);
          },
          error: () => {
            this.emailChangeLoading.set(false);
            this.emailChangeSuccess.set(true);
            this.loginEmail.set(email);
          },
        });
      },
      error: (err) => {
        this.emailChangeLoading.set(false);
        this.emailChangeError.set(translateError(err, this.transloco));
      },
    });
  }

  protected readonly visibilityIsAnonymous = computed(() => this.visibility() === ProfileVisibility.Anonymous);
}
