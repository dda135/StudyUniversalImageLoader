/*******************************************************************************
 * Copyright 2013-2014 Sergey Tarasevich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.nostra13.universalimageloader.utils;

import android.graphics.BitmapFactory;
import android.opengl.GLES10;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;
import com.nostra13.universalimageloader.core.imageaware.ImageAware;

import javax.microedition.khronos.opengles.GL10;

/**
 * Provides calculations with image sizes, scales
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.8.3
 */
public final class ImageSizeUtils {

	private static final int DEFAULT_MAX_BITMAP_DIMENSION = 2048;

	private static ImageSize maxBitmapSize;

	static {
		int[] maxTextureSize = new int[1];
		GLES10.glGetIntegerv(GL10.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
		int maxBitmapDimension = Math.max(maxTextureSize[0], DEFAULT_MAX_BITMAP_DIMENSION);
		maxBitmapSize = new ImageSize(maxBitmapDimension, maxBitmapDimension);
	}

	private ImageSizeUtils() {
	}

	/**
	 * Defines target size for image aware view. Size is defined by target
	 * {@link com.nostra13.universalimageloader.core.imageaware.ImageAware view} parameters, configuration
	 * parameters or device display dimensions.<br />
	 */
	public static ImageSize defineTargetSizeForView(ImageAware imageAware, ImageSize maxImageSize) {
		int width = imageAware.getWidth();
		if (width <= 0) width = maxImageSize.getWidth();

		int height = imageAware.getHeight();
		if (height <= 0) height = maxImageSize.getHeight();

		return new ImageSize(width, height);
	}

	private static int considerMaxTextureSize(int srcWidth, int srcHeight, int scale, boolean powerOf2) {
		final int maxWidth = maxBitmapSize.getWidth();
		final int maxHeight = maxBitmapSize.getHeight();
		while ((srcWidth / scale) > maxWidth || (srcHeight / scale) > maxHeight) {
			if (powerOf2) {
				scale *= 2;
			} else {
				scale++;
			}
		}
		return scale;
	}

	/**
	 * Computes minimal sample size for downscaling image so result image size won't exceed max acceptable OpenGL
	 * texture size.<br />
	 * We can't create Bitmap in memory with size exceed max texture size (usually this is 2048x2048) so this method
	 * calculate minimal sample size which should be applied to image to fit into these limits.
	 *
	 * @param srcSize Original image size
	 * @return Minimal sample size
	 */
	public static int computeMinImageSampleSize(ImageSize srcSize) {
		final int srcWidth = srcSize.getWidth();
		final int srcHeight = srcSize.getHeight();
		//注释中说明这个值一般都是2048x2048
		final int targetWidth = maxBitmapSize.getWidth();
		final int targetHeight = maxBitmapSize.getHeight();
		//基本可以认为这个值为1（向上取整）
		//只有图片大于2048x2048的情况下才压缩
		final int widthScale = (int) Math.ceil((float) srcWidth / targetWidth);
		final int heightScale = (int) Math.ceil((float) srcHeight / targetHeight);

		return Math.max(widthScale, heightScale); // max
	}

	/**
	 * Computes sample size for downscaling image size (<b>srcSize</b>) to view size (<b>targetSize</b>). This sample
	 * size is used during
	 * {@linkplain BitmapFactory#decodeStream(java.io.InputStream, android.graphics.Rect, android.graphics.BitmapFactory.Options)
	 * decoding image} to bitmap.<br />
	 * <br />
	 * <b>Examples:</b><br />
	 * <p/>
	 * <pre>
	 * srcSize(100x100), targetSize(10x10), powerOf2Scale = true -> sampleSize = 8
	 * srcSize(100x100), targetSize(10x10), powerOf2Scale = false -> sampleSize = 10
	 *
	 * srcSize(100x100), targetSize(20x40), viewScaleType = FIT_INSIDE -> sampleSize = 5
	 * srcSize(100x100), targetSize(20x40), viewScaleType = CROP       -> sampleSize = 2
	 * </pre>
	 * <p/>
	 * <br />
	 * The sample size is the number of pixels in either dimension that correspond to a single pixel in the decoded
	 * bitmap. For example, inSampleSize == 4 returns an image that is 1/4 the width/height of the original, and 1/16
	 * the number of pixels. Any value <= 1 is treated the same as 1.
	 *
	 * @param srcSize       Original (image) size
	 * @param targetSize    Target (view) size
	 * @param viewScaleType {@linkplain ViewScaleType Scale type} for placing image in view
	 * @param powerOf2Scale <i>true</i> - if sample size be a power of 2 (1, 2, 4, 8, ...)
	 * @return Computed sample size
	 */
	public static int computeImageSampleSize(ImageSize srcSize, ImageSize targetSize, ViewScaleType viewScaleType,
			boolean powerOf2Scale) {
		//获取Bitmap的原始宽高
		final int srcWidth = srcSize.getWidth();
		final int srcHeight = srcSize.getHeight();
		//获取载体的宽高
		final int targetWidth = targetSize.getWidth();
		final int targetHeight = targetSize.getHeight();

		int scale = 1;
		//inSampleSize是用于压缩的，一般来说2的话就意味着宽和高都变成原来的一般，从像素和内存的角度就是变成了1/4
		switch (viewScaleType) {
			case FIT_INSIDE://对应于FIT_XY系列
				if (powerOf2Scale) {//ImageScaleType.IN_SAMPLE_POWER_OF_2
					final int halfWidth = srcWidth / 2;
					final int halfHeight = srcHeight / 2;
					//循环2除，直到bitmap的宽高都小于等于载体的宽高
					//FIT系列在设置的时候会自动拉伸，所以说FIT_XY会有拉伸
					//注意到这里如果视图没有绘制完成或者wrap_content，并且没有指定maxHeight/Width的时候调用
					//此时target默认为屏幕，图片基本可以认为不会进行压缩
					while ((halfWidth / scale) > targetWidth || (halfHeight / scale) > targetHeight) {
						scale *= 2;
					}
				} else {//IN_SAMPLE_INT、EXACTLY、EXACTLY_STRETCHED
					//这种方式不一定会得到2的倍数，但是BitmapFactory的inSampleSize只支持2的倍数
					//其中BitmapFactory会自动向下取，比方说这里取7，但是实际上用的是4，会比预期要大
					scale = Math.max(srcWidth / targetWidth, srcHeight / targetHeight);
				}
				break;
			case CROP://对应于CROP系列
				if (powerOf2Scale) {
					final int halfWidth = srcWidth / 2;
					final int halfHeight = srcHeight / 2;
					//只要有宽/高压缩到target的宽/高即可，就是说可能存在另一边大于target的情况
					while ((halfWidth / scale) > targetWidth && (halfHeight / scale) > targetHeight) {
						scale *= 2;
					}
				} else {
					//这种方式不一定会得到2的倍数，但是BitmapFactory的inSampleSize只支持2的倍数
					//其中BitmapFactory会自动向下取，比方说这里取7，但是实际上用的是4
					//这里预期是宽/高有一边到达target的宽/高即可，这样会导致可能两边都比target大
					scale = Math.min(srcWidth / targetWidth, srcHeight / targetHeight);
				}
				break;
		}

		if (scale < 1) {//注意这里是没有拉伸操作的，当然inSampleSize也不支持<1，默认也会使用1
			scale = 1;
		}
		//不允许大于2048x2048
		scale = considerMaxTextureSize(srcWidth, srcHeight, scale, powerOf2Scale);
		//如果不采用2除的方式，inSampleSize默认会取1,2,4,8...，从而向下取整
		return scale;
	}

	/**
	 * Computes scale of target size (<b>targetSize</b>) to source size (<b>srcSize</b>).<br />
	 * <br />
	 * <b>Examples:</b><br />
	 * <p/>
	 * <pre>
	 * srcSize(40x40), targetSize(10x10) -> scale = 0.25
	 *
	 * srcSize(10x10), targetSize(20x20), stretch = false -> scale = 1
	 * srcSize(10x10), targetSize(20x20), stretch = true  -> scale = 2
	 *
	 * srcSize(100x100), targetSize(20x40), viewScaleType = FIT_INSIDE -> scale = 0.2
	 * srcSize(100x100), targetSize(20x40), viewScaleType = CROP       -> scale = 0.4
	 * </pre>
	 *
	 * @param srcSize       Source (image) size
	 * @param targetSize    Target (view) size
	 * @param viewScaleType {@linkplain ViewScaleType Scale type} for placing image in view
	 * @param stretch       Whether source size should be stretched if target size is larger than source size. If <b>false</b>
	 *                      then result scale value can't be greater than 1.
	 * @return Computed scale
	 */
	public static float computeImageScale(ImageSize srcSize, ImageSize targetSize, ViewScaleType viewScaleType,
			boolean stretch) {
		//压缩后的bitmap的宽高
		final int srcWidth = srcSize.getWidth();
		final int srcHeight = srcSize.getHeight();
		//载体的宽高
		final int targetWidth = targetSize.getWidth();
		final int targetHeight = targetSize.getHeight();

		final float widthScale = (float) srcWidth / targetWidth;
		final float heightScale = (float) srcHeight / targetHeight;

		final int destWidth;
		final int destHeight;
		//将宽/高其中的靠近载体的一边变成target的大小，另一边按照比例拉伸或者压缩
		if ((viewScaleType == ViewScaleType.FIT_INSIDE && widthScale >= heightScale) ||
				(viewScaleType == ViewScaleType.CROP && widthScale < heightScale)) {
			destWidth = targetWidth;
			destHeight = (int) (srcHeight / widthScale);
		} else {
			destWidth = (int) (srcWidth / heightScale);
			destHeight = targetHeight;
		}

		float scale = 1;
		//EXACTLY的时候只会进行压缩
		//FIT系列会按照最大边进行压缩，那么压缩完之后bitmap的宽高必然小于载体
		//CROP系列会按照最小边进行压缩，那么压缩完是保证至少有一边满足载体要求
		//EXACTLY_STRETCHED会进行压缩或者拉伸，压缩的表现同EXACTLY
		//FIT系列会进行拉伸，知道有一边满足载体要求
		//CROP系列在压缩的时候，基本上会保证bitmap大于等于载体，拉伸基本上没啥可能
		//一般来说提供相同比例的图片，那么就是宽高都是完全匹配的，这两种模式没有任何区别
		if ((!stretch && destWidth < srcWidth && destHeight < srcHeight) || (stretch && destWidth != srcWidth && destHeight != srcHeight)) {
			scale = (float) destWidth / srcWidth;
		}

		return scale;
	}
}
