/*******************************************************************************
 * Copyright 2014 Sergey Tarasevich
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
package com.nostra13.universalimageloader.core.imageaware;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;
import com.nostra13.universalimageloader.utils.L;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

/**
 * Wrapper for Android {@link android.view.View View}. Keeps weak reference of View to prevent memory leaks.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.9.2
 */
public abstract class ViewAware implements ImageAware {

	public static final String WARN_CANT_SET_DRAWABLE = "Can't set a drawable into view. You should call ImageLoader on UI thread for it.";
	public static final String WARN_CANT_SET_BITMAP = "Can't set a bitmap into view. You should call ImageLoader on UI thread for it.";

	protected Reference<View> viewRef;//这里实际上是一个弱应用，这样在View被GC之后可以释放对应的Activity，不会造成内存泄漏问题
	protected boolean checkActualViewSize;//这个默认一般为true，会尝试通过getWidth/Height的方式获取大小

	/**
	 * Constructor. <br />
	 * References {@link #ViewAware(android.view.View, boolean) ImageViewAware(imageView, true)}.
	 *
	 * @param view {@link android.view.View View} to work with
	 */
	public ViewAware(View view) {
		this(view, true);
	}

	/**
	 * Constructor
	 *
	 * @param view                {@link android.view.View View} to work with
	 * @param checkActualViewSize <b>true</b> - then {@link #getWidth()} and {@link #getHeight()} will check actual
	 *                            size of View. It can cause known issues like
	 *                            <a href="https://github.com/nostra13/Android-Universal-Image-Loader/issues/376">this</a>.
	 *                            But it helps to save memory because memory cache keeps bitmaps of actual (less in
	 *                            general) size.
	 *                            <p/>
	 *                            <b>false</b> - then {@link #getWidth()} and {@link #getHeight()} will <b>NOT</b>
	 *                            consider actual size of View, just layout parameters. <br /> If you set 'false'
	 *                            it's recommended 'android:layout_width' and 'android:layout_height' (or
	 *                            'android:maxWidth' and 'android:maxHeight') are set with concrete values. It helps to
	 *                            save memory.
	 */
	public ViewAware(View view, boolean checkActualViewSize) {
		if (view == null) throw new IllegalArgumentException("view must not be null");

		this.viewRef = new WeakReference<View>(view);
		this.checkActualViewSize = checkActualViewSize;
	}

	/**
	 * {@inheritDoc}
	 * <p/>
	 * Width is defined by target {@link android.view.View view} parameters, configuration
	 * parameters or device display dimensions.<br />
	 * Size computing algorithm (go by steps until get non-zero value):<br />
	 * 1) Get the actual drawn <b>getWidth()</b> of the View<br />
	 * 2) Get <b>layout_width</b>
	 */
	@Override
	public int getWidth() {
		View view = viewRef.get();
		if (view != null) {
			final ViewGroup.LayoutParams params = view.getLayoutParams();
			int width = 0;
			//checkActualViewSize默认为true，当不是wrap_content的情况获取视图的宽度
			if (checkActualViewSize && params != null && params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
				width = view.getWidth(); //这里要注意，如果视图第一次layout没有完成的话这个肯定是0咯，尽量在视图绘制完成后使用合适一点
			}
			if (width <= 0 && params != null) width = params.width;
			//这里当视图绘制没有完成和wrap_content的时候可能为0或-2
			return width;
		}
		return 0;
	}

	/**
	 * {@inheritDoc}
	 * <p/>
	 * Height is defined by target {@link android.view.View view} parameters, configuration
	 * parameters or device display dimensions.<br />
	 * Size computing algorithm (go by steps until get non-zero value):<br />
	 * 1) Get the actual drawn <b>getHeight()</b> of the View<br />
	 * 2) Get <b>layout_height</b>
	 */
	@Override
	public int getHeight() {
		View view = viewRef.get();
		if (view != null) {
			final ViewGroup.LayoutParams params = view.getLayoutParams();
			int height = 0;
			if (checkActualViewSize && params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
				height = view.getHeight(); // Get actual image height
			}
			if (height <= 0 && params != null) height = params.height; // Get layout height parameter
			return height;
		}
		return 0;
	}

	@Override
	public ViewScaleType getScaleType() {
		return ViewScaleType.CROP;
	}

	@Override
	public View getWrappedView() {
		return viewRef.get();
	}

	@Override
	public boolean isCollected() {
		return viewRef.get() == null;//通过弱应用持有的对象，如果没有其它引用的情况下，在系统GC的时候也会被回收
	}

	@Override
	public int getId() {
		View view = viewRef.get();//这里通过view的hashCode返回唯一标识符，可以应对视图复用的情况
		return view == null ? super.hashCode() : view.hashCode();
	}

	@Override
	public boolean setImageDrawable(Drawable drawable) {
		//当前方法可能在UI线程或者子线程中回调
		//此处只允许在UI线程中设置bitmap
		if (Looper.myLooper() == Looper.getMainLooper()) {
			View view = viewRef.get();
			if (view != null) {//检查view是否被回收
				setImageDrawableInto(drawable, view);//当前没有被回收
				return true;
			}
		} else {
			L.w(WARN_CANT_SET_DRAWABLE);
		}
		return false;
	}

	@Override
	public boolean setImageBitmap(Bitmap bitmap) {
		//当前方法可能在UI线程或者子线程中回调
		//此处只允许在UI线程中设置bitmap
		if (Looper.myLooper() == Looper.getMainLooper()) {
			View view = viewRef.get();
			if (view != null) {//检查view是否被回收
				setImageBitmapInto(bitmap, view);//当前没有被回收
				return true;
			}
		} else {
			L.w(WARN_CANT_SET_BITMAP);
		}
		return false;
	}

	/**
	 * 必定调用在UI线程
	 * 此时view必定不为null，此时可以尝试设置drawable
	 */
	protected abstract void setImageDrawableInto(Drawable drawable, View view);

	/**
	 * 必定调用在UI线程
	 * 此时view必定不为null，此时可以尝试设置bitmap
	 */
	protected abstract void setImageBitmapInto(Bitmap bitmap, View view);
}
