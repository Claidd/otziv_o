import { Injectable } from '@angular/core';
import { Camera, MediaTypeSelection, type MediaResult } from '@capacitor/camera';
import { Capacitor } from '@capacitor/core';

type ImageCompressionOptions = {
  maxDimension: number;
  maxBytes: number;
  quality: number;
};

const DEFAULT_IMAGE_COMPRESSION: ImageCompressionOptions = {
  maxDimension: 1600,
  maxBytes: 900 * 1024,
  quality: 0.82
};

@Injectable({ providedIn: 'root' })
export class MobileMediaService {
  readonly nativePhotoPickerAvailable = Capacitor.isNativePlatform() && Capacitor.isPluginAvailable('Camera');

  async pickImageFile(fileNamePrefix = 'otziv-photo'): Promise<File | null> {
    if (!this.nativePhotoPickerAvailable) {
      return null;
    }

    const result = await Camera.chooseFromGallery({
      mediaType: MediaTypeSelection.Photo,
      allowMultipleSelection: false,
      includeMetadata: true,
      editable: 'no'
    }).catch(() => null);

    const media = result?.results?.[0];
    if (!media?.webPath) {
      return null;
    }

    return this.mediaToFile(media, fileNamePrefix);
  }

  async prepareImageFile(
    file: File,
    fileNamePrefix = this.fileNameBase(file.name) || 'otziv-photo',
    options: Partial<ImageCompressionOptions> = {}
  ): Promise<File> {
    if (!file.type.startsWith('image/') || file.type === 'image/gif') {
      return file;
    }

    const compression = { ...DEFAULT_IMAGE_COMPRESSION, ...options };
    try {
      const image = await this.loadImage(file);
      const imageUrl = image.src;
      const largestSide = Math.max(image.naturalWidth, image.naturalHeight);
      const scale = Math.min(1, compression.maxDimension / largestSide);

      if (scale >= 1 && file.size <= compression.maxBytes) {
        URL.revokeObjectURL(imageUrl);
        return file;
      }

      const width = Math.max(1, Math.round(image.naturalWidth * scale));
      const height = Math.max(1, Math.round(image.naturalHeight * scale));
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;

      const context = canvas.getContext('2d');
      if (!context) {
        URL.revokeObjectURL(imageUrl);
        return file;
      }

      context.drawImage(image, 0, 0, width, height);
      const blob = await this.canvasToBlob(canvas, compression.quality);
      URL.revokeObjectURL(imageUrl);

      if (!blob || blob.size >= file.size) {
        return file;
      }

      return new File([blob], `${fileNamePrefix}-${Date.now()}.jpg`, { type: 'image/jpeg' });
    } catch {
      return file;
    }
  }

  private async mediaToFile(media: MediaResult, fileNamePrefix: string): Promise<File> {
    const response = await fetch(media.webPath!);
    const blob = await response.blob();
    const type = blob.type || this.mimeFromFormat(media.metadata?.format);
    const extension = this.extensionFromMime(type);
    const file = new File([blob], `${fileNamePrefix}-${Date.now()}.${extension}`, { type });
    return this.prepareImageFile(file, fileNamePrefix);
  }

  private loadImage(file: File): Promise<HTMLImageElement> {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => {
        URL.revokeObjectURL(url);
        reject(new Error('Не удалось прочитать изображение.'));
      };
      image.src = url;
    });
  }

  private canvasToBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob | null> {
    return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
  }

  private fileNameBase(name: string): string {
    return name.replace(/\.[^.]+$/, '').replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-+|-+$/g, '');
  }

  private mimeFromFormat(format?: string): string {
    const normalized = format?.toLowerCase();
    if (normalized === 'png') {
      return 'image/png';
    }
    if (normalized === 'webp') {
      return 'image/webp';
    }
    return 'image/jpeg';
  }

  private extensionFromMime(mime: string): string {
    if (mime.includes('png')) {
      return 'png';
    }
    if (mime.includes('webp')) {
      return 'webp';
    }
    return 'jpg';
  }
}
