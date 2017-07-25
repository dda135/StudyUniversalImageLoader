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
package com.nostra13.universalimageloader.core.imageaware;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;

/**
 * Represents image aware view which provides all needed properties and behavior for image processing and displaying
 * through {@link com.nostra13.universalimageloader.core.ImageLoader ImageLoader}.
 * It can wrap any Android {@link android.view.View View} which can be accessed by {@link #getWrappedView()}. Wrapped
 * view is returned in {@link com.nostra13.universalimageloader.core.listener.ImageLoadingListener ImageLoadingListener}'s
 * callbacks.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @see ViewAware
 * @see ImageViewAware
 * @see NonViewAware
 * @since 1.9.0
 */
public interface ImageAware {
	/**
	 * 返回图片载体的宽度，实际上也是定义图片拉伸的大小。
	 * 可以返回0，如果当前无法获得宽度
	 * 如果在主线程调用ImageLoader，则该方法回调在主线程，否则在子线程
	 */
	int getWidth();

	/**
	 * 返回图片载体的高度，实际上也是定义图片拉伸的大小。
	 * 可以返回0，如果当前无法获得高度
	 * 如果在主线程调用ImageLoader，则该方法回调在主线程，否则在子线程
	 */
	int getHeight();

	/**
	 *	获得想要的图片的拉伸模式，一般会在拉伸和展示的时候有所区别
	 */
	ViewScaleType getScaleType();

	/**
	 * 返回被装饰后的视图。
	 * 可能为null，如果没有视图被装饰或者说视图被回收了
	 */
	View getWrappedView();

	/**
	 * 返回一个标志，用于告知ImageLoader当前载体是否有效，如果的当前载体被回收，那么
	 * ImageLoader应该停止对应的进行中的图片加载任务，并且进行ImageLoadingListener的onLoadingCancelled(String, View)回调
	 * 如果在主线程调用ImageLoader，则该方法回调在主线程，否则在子线程
	 *
	 * @return true表示当前视图已经被GC，ImageLoader不应该继续工作。
	 */
	boolean isCollected();

	/**
	 * 返回当前载体对应的id。这个id应该是唯一的，同样的会对应于ImageLoader中的任务，对于同样id的载体来说，
	 * 不应该开始多个任务，而是会取消掉旧的任务，只执行最新的任务
	 * 这个有必要返回被装饰的view的hashCode()，这样可以避免因为视图复用导致展示错误的图片。
	 */
	int getId();

	/**
	 * 用于设置加载并且处理后的drawable到指定的载体上面
	 * 下面是一些常用的方法：
	 * {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions.Builder#showImageForEmptyUri(
	 *android.graphics.drawable.Drawable) for empty Uri},
	 * {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions.Builder#showImageOnLoading(
	 *android.graphics.drawable.Drawable) on loading} or
	 * {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions.Builder#showImageOnFail(
	 *android.graphics.drawable.Drawable) on loading fail}. These drawables can be specified in
	 * {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions display options}.<br />
	 * Also can be called in {@link com.nostra13.universalimageloader.core.display.BitmapDisplayer BitmapDisplayer}.< br />
	 * 如果在主线程调用ImageLoader，则该方法回调在主线程，否则在子线程
	 *
	 * @return true表示drawable成功设置
	 */
	boolean setImageDrawable(Drawable drawable);

	/**
	 * 用于设置加载并且处理后的bitmap到指定的载体上面
	 * 可能通过该方法回调
	 * {@link com.nostra13.universalimageloader.core.display.BitmapDisplayer BitmapDisplayer}.< br />
	 * 如果在主线程调用ImageLoader，则该方法回调在主线程，否则在子线程
	 *
	 * @return true表示bitmap成功设置
	 */
	boolean setImageBitmap(Bitmap bitmap);
}
