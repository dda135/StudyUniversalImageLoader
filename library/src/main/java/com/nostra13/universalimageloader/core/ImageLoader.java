/*******************************************************************************
 * Copyright 2011-2014 Sergey Tarasevich
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
package com.nostra13.universalimageloader.core;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import com.nostra13.universalimageloader.cache.disc.DiskCache;
import com.nostra13.universalimageloader.cache.memory.MemoryCache;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.FlushedInputStream;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.assist.LoadedFrom;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;
import com.nostra13.universalimageloader.core.imageaware.ImageAware;
import com.nostra13.universalimageloader.core.imageaware.ImageViewAware;
import com.nostra13.universalimageloader.core.imageaware.NonViewAware;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener;
import com.nostra13.universalimageloader.core.listener.SimpleImageLoadingListener;
import com.nostra13.universalimageloader.utils.ImageSizeUtils;
import com.nostra13.universalimageloader.utils.L;
import com.nostra13.universalimageloader.utils.MemoryCacheUtils;

/**
 * Singletone for image loading and displaying at {@link ImageView ImageViews}<br />
 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before any other method.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.0.0
 */
public class ImageLoader {

	public static final String TAG = ImageLoader.class.getSimpleName();

	static final String LOG_INIT_CONFIG = "Initialize ImageLoader with configuration";
	static final String LOG_DESTROY = "Destroy ImageLoader";
	static final String LOG_LOAD_IMAGE_FROM_MEMORY_CACHE = "Load image from memory cache [%s]";

	private static final String WARNING_RE_INIT_CONFIG = "Try to initialize ImageLoader which had already been initialized before. " + "To re-init ImageLoader with new configuration call ImageLoader.destroy() at first.";
	private static final String ERROR_WRONG_ARGUMENTS = "Wrong arguments were passed to displayImage() method (ImageView reference must not be null)";
	private static final String ERROR_NOT_INIT = "ImageLoader must be init with configuration before using";
	private static final String ERROR_INIT_CONFIG_WITH_NULL = "ImageLoader configuration can not be initialized with null";
	//全局配置，有且只有一个，多次配置只以第一个为准
	private ImageLoaderConfiguration configuration;
	//这个其实是线程管理类，顾名思义就是引擎吧
	private ImageLoaderEngine engine;
	//读取流程监听，默认是空实现
	private ImageLoadingListener defaultListener = new SimpleImageLoadingListener();
	//单例实现类，DCL结合volatile才可以真正实现完整单例
	private volatile static ImageLoader instance;

	/**
	 * DCL单例实现
	 * */
	public static ImageLoader getInstance() {
		if (instance == null) {
			synchronized (ImageLoader.class) {
				if (instance == null) {
					instance = new ImageLoader();
				}
			}
		}
		return instance;
	}

	protected ImageLoader() {
	}

	/**
	 * 初始化ImageLoader的全局配置，同时也是ImageLoaderEngine的配置
	 * 如果说在已经设置了一个ImageLoaderConfiguration的情况下再次设置
	 * 该方法并不会生效
	 * 如果想要重新设置配置，那么首先需要destory()整个ImageLoader
	 * 然后再重新设置
	 */
	public synchronized void init(ImageLoaderConfiguration configuration) {
		if (configuration == null) {
			throw new IllegalArgumentException(ERROR_INIT_CONFIG_WITH_NULL);
		}
		//注意这里配置只能设置一次，多次init也只能以第一次为准
		//主要是因为这个配置会新建一个运行引擎，所以说在旧的引擎没有停止之前
		//新设置的配置都不会生效
		if (this.configuration == null) {
			L.d(LOG_INIT_CONFIG);
			engine = new ImageLoaderEngine(configuration);
			this.configuration = configuration;
		} else {
			L.w(WARNING_RE_INIT_CONFIG);
		}
	}

	/**
	 * Returns <b>true</b> - if ImageLoader {@linkplain #init(ImageLoaderConfiguration) is initialized with
	 * configuration}; <b>false</b> - otherwise
	 */
	public boolean isInited() {
		return configuration != null;
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn. <br/>
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri        Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                   which should display image
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware) {
		displayImage(uri, imageAware, null, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri        Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                   which should display image
	 * @param listener   {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on
	 *                   UI thread if this method is called on UI thread.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, ImageLoadingListener listener) {
		displayImage(uri, imageAware, null, listener, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri        Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                   which should display image
	 * @param options    {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                   decoding and displaying. If <b>null</b> - default display image options
	 *                   {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                   from configuration} will be used.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options) {
		displayImage(uri, imageAware, options, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri        Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                   which should display image
	 * @param options    {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                   decoding and displaying. If <b>null</b> - default display image options
	 *                   {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                   from configuration} will be used.
	 * @param listener   {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on
	 *                   UI thread if this method is called on UI thread.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options,
			ImageLoadingListener listener) {
		displayImage(uri, imageAware, options, listener, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageAware when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri              Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageAware       {@linkplain com.nostra13.universalimageloader.core.imageaware.ImageAware Image aware view}
	 *                         which should display image
	 * @param options          {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                         decoding and displaying. If <b>null</b> - default display image options
	 *                         {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                         from configuration} will be used.
	 * @param listener         {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                         events on UI thread if this method is called on UI thread.
	 * @param progressListener {@linkplain com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener
	 *                         Listener} for image loading progress. Listener fires events on UI thread if this method
	 *                         is called on UI thread. Caching on disk should be enabled in
	 *                         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions options} to make
	 *                         this listener work.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageAware</b> is null
	 */
	public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options,
			ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		displayImage(uri, imageAware, options, null, listener, progressListener);
	}

	/**
	 * 添加一个加载图片，并且展示图片的任务到执行池中。
	 * 后续图片会自动展示到指定的载体上面，如果有必要的话
	 * ImageLoader如果需要运作的话，必须要保证调用init(ImageLoaderConfiguration)初始化了ImageLoaderEngine
	 * 否则并没有可以使用的执行池
	 *
	 * @param uri 图片的URI，网络图片"http://site.com/image.png" ，手机硬盘上面的图片"file:///mnt/sdcard/image.png" 等等
	 * @param imageAware 图片的载体，一般用于展示图片
	 * @param options 图片解析和展示所使用的配置。可以为null，会使用默认的DisplayImageOptions
	 * @param targetSize 图片的尺寸，可以为null，这样图片的尺寸会取决与view的测量情况
	 * @param listener 加载图片状态变化的监听，如果该方法调用在UI线程，那么监听的回调也会在UI线程，
	 *                 请求uri为空的时候依然会工作
	 * @param progressListener 加载图片完成度的监听，如果该方法调用在UI线程，那么监听的回调也会在UI线程，
	 *                            如果说该图片从硬盘缓存中获取，那么此监听也会工作
	 */
	public void displayImage(String uri, ImageAware imageAware, DisplayImageOptions options,
			ImageSize targetSize, ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		//ImageLoader在使用之前必须初始化ImageLoaderConfiguration
		checkConfiguration();
		if (imageAware == null) {
			throw new IllegalArgumentException(ERROR_WRONG_ARGUMENTS);
		}
		if (listener == null) {//默认的listener为空实现
			listener = defaultListener;
		}
		if (options == null) {//默认的解析和加载配置，不会使用缓存机制
			options = configuration.defaultDisplayImageOptions;
		}

		if (TextUtils.isEmpty(uri)) {//请求的链接为null或者""...这里还是需要手动过滤空格
			//ImageLoader为载体设置了唯一的标识符，意思其实就是每一个载体在某一个时刻应该只需要接受一个图片展示任务
			//因为当前图片加载和展示是最新的请求，但是因为空，所以不需要开始任务，不过还有有必要要取消之前旧的请求
			engine.cancelDisplayTaskFor(imageAware);
			//回调开始加载状态
			listener.onLoadingStarted(uri, imageAware.getWrappedView());
			//如果在DisplayOption中设置了空链接的情况下使用的资源图片，则尝试使用该资源图片
			if (options.shouldShowImageForEmptyUri()) {
				imageAware.setImageDrawable(options.getImageForEmptyUri(configuration.resources));
			} else {
				imageAware.setImageDrawable(null);
			}
			//回调加载结束状态，注意这里回调的Bitmap为null
			listener.onLoadingComplete(uri, imageAware.getWrappedView(), null);
			//可以看到如果请求的链接为空，它会停止之前的请求，
			//一个比较直接的结束之前请求并且设置想要的资源的方式可以采用这种方式
			return;
		}
		//图片想要的尺寸，这个后期主要用于压缩处理的参数，默认都是null
		if (targetSize == null) {
			//这里简单理解就是载体的宽度
			//有几种情况比较特殊
			//在ImageView是wrap_content或者测量没有完成的情况下getWidth/Height为0、并且没有设置maxWidth的情况下
			//使用配置的值或者屏幕的宽高，总之最后一定会有值
			targetSize = ImageSizeUtils.defineTargetSizeForView(imageAware, configuration.getMaxImageSize());
		}
		//获取缓存的key值，格式为链接_载体宽度x载体高度
		//即同一个图片地址，对于不同大小的载体来说内存缓存是不同的，这点需要注意
		String memoryCacheKey = MemoryCacheUtils.generateKey(uri, targetSize);
		//记录当前请求的载体及其对于的内存缓存key值
		//这个主要用于标记当前载体对应的请求，可以有效防止重复或乱序加载
		engine.prepareDisplayTaskFor(imageAware, memoryCacheKey);
		//回调开始加载状态
		listener.onLoadingStarted(uri, imageAware.getWrappedView());
		//首先从内存缓存中获取，默认配置的是LruMemoryCache，大小是当前可分配内存的1/8
		Bitmap bmp = configuration.memoryCache.get(memoryCacheKey);
		if (bmp != null && !bmp.isRecycled()) {
			//内存缓存命中
			L.d(LOG_LOAD_IMAGE_FROM_MEMORY_CACHE, memoryCacheKey);
			//如果需要对获得的Bitmap进行额外(拉伸之类的)操作，可以在DisplayOption中设置postProcessor
			if (options.shouldPostProcess()) {
				//初始化加载信息，就是设置一堆参数
				//其中对每一个不同的URI都会拥有各自的ReentrantLock锁
				ImageLoadingInfo imageLoadingInfo = new ImageLoadingInfo(uri, imageAware, targetSize, memoryCacheKey,
						options, listener, progressListener, engine.getLockForUri(uri));
				//内部分两步操作
				//首先是获得对应的postProcess，并且执行对bitmap的操作
				//然后就是开始执行展示bitmap的任务
				//如果设置了syncLoading，即同步加载，那么整个流程都会在run的线程中进行
				//否则会先进入子线程中执行postProcess，然后在指定的handler中执行展示任务（这个一般会有修改UI的操作，所以一般都是回到UI线程）
				ProcessAndDisplayImageTask displayTask = new ProcessAndDisplayImageTask(engine, bmp, imageLoadingInfo,
						defineHandler(options));
				if (options.isSyncLoading()) {//同步执行，那么直接在displayImage的调用线程中运行任务
					displayTask.run();
				} else {//否则进入执行池的子线程中
					engine.submit(displayTask);
				}
			} else {
				//不需要对获得的bitmap做什么操作，直接展示并进行对于状态回调即可
				options.getDisplayer().display(bmp, imageAware, LoadedFrom.MEMORY_CACHE);
				listener.onLoadingComplete(uri, imageAware.getWrappedView(), bmp);
			}
			//可以看到，如果击中内存的话则不会显示Loading中图片，这样做有一个主要的好处就是在ListView中滑动加载已经加载的图片的时候不会抖动
		} else {//没有击中内存缓存
			if (options.shouldShowImageOnLoading()) {//如果在DisplayImageOptions中指定Loading中资源图片，则直接展示Loading图片
				imageAware.setImageDrawable(options.getImageOnLoading(configuration.resources));
			} else if (options.isResetViewBeforeLoading()) {//如果在DisplayImageOptions中没有指定Loading中资源图片
				//如果此时在DisplayImageOptions中设置加载前需要重置载体中的图片资源，则尝试进行载体的图片资源重置
				imageAware.setImageDrawable(null);
			}
			//设置加载中参数
			ImageLoadingInfo imageLoadingInfo = new ImageLoadingInfo(uri, imageAware, targetSize, memoryCacheKey,
					options, listener, progressListener, engine.getLockForUri(uri));
			//加载图片，并且展示图片的任务
			LoadAndDisplayImageTask displayTask = new LoadAndDisplayImageTask(engine, imageLoadingInfo,
					defineHandler(options));
			if (options.isSyncLoading()) {//同步，在当前线程执行
				displayTask.run();
			} else {//否则进入线程池中的子线程
				engine.submit(displayTask);
			}
		}
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn. <br/>
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView) {
		displayImage(uri, new ImageViewAware(imageView), null, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn. <br/>
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, ImageSize targetImageSize) {
		displayImage(uri, new ImageViewAware(imageView), null, targetImageSize, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @param options   {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                  decoding and displaying. If <b>null</b> - default display image options
	 *                  {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                  from configuration} will be used.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, DisplayImageOptions options) {
		displayImage(uri, new ImageViewAware(imageView), options, null, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn.<br />
	 * Default {@linkplain DisplayImageOptions display image options} from {@linkplain ImageLoaderConfiguration
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @param listener  {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on
	 *                  UI thread if this method is called on UI thread.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, ImageLoadingListener listener) {
		displayImage(uri, new ImageViewAware(imageView), null, listener, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri       Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView {@link ImageView} which should display image
	 * @param options   {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                  decoding and displaying. If <b>null</b> - default display image options
	 *                  {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                  from configuration} will be used.
	 * @param listener  {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on
	 *                  UI thread if this method is called on UI thread.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, DisplayImageOptions options,
			ImageLoadingListener listener) {
		displayImage(uri, imageView, options, listener, null);
	}

	/**
	 * Adds display image task to execution pool. Image will be set to ImageView when it's turn.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri              Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param imageView        {@link ImageView} which should display image
	 * @param options          {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                         decoding and displaying. If <b>null</b> - default display image options
	 *                         {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                         from configuration} will be used.
	 * @param listener         {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                         events on UI thread if this method is called on UI thread.
	 * @param progressListener {@linkplain com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener
	 *                         Listener} for image loading progress. Listener fires events on UI thread if this method
	 *                         is called on UI thread. Caching on disk should be enabled in
	 *                         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions options} to make
	 *                         this listener work.
	 * @throws IllegalStateException    if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @throws IllegalArgumentException if passed <b>imageView</b> is null
	 */
	public void displayImage(String uri, ImageView imageView, DisplayImageOptions options,
			ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		displayImage(uri, new ImageViewAware(imageView), options, listener, progressListener);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri      Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param listener {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on UI
	 *                 thread if this method is called on UI thread.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, ImageLoadingListener listener) {
		loadImage(uri, null, null, listener, null);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri             Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param targetImageSize Minimal size for {@link Bitmap} which will be returned in
	 *                        {@linkplain ImageLoadingListener#onLoadingComplete(String, android.view.View,
	 *                        android.graphics.Bitmap)} callback}. Downloaded image will be decoded
	 *                        and scaled to {@link Bitmap} of the size which is <b>equal or larger</b> (usually a bit
	 *                        larger) than incoming targetImageSize.
	 * @param listener        {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                        events on UI thread if this method is called on UI thread.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, ImageSize targetImageSize, ImageLoadingListener listener) {
		loadImage(uri, targetImageSize, null, listener, null);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri      Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param options  {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                 decoding and displaying. If <b>null</b> - default display image options
	 *                 {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions) from
	 *                 configuration} will be used.<br />
	 * @param listener {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires events on UI
	 *                 thread if this method is called on UI thread.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, DisplayImageOptions options, ImageLoadingListener listener) {
		loadImage(uri, null, options, listener, null);
	}

	/**
	 * Adds load image task to execution pool. Image will be returned with
	 * {@link ImageLoadingListener#onLoadingComplete(String, android.view.View, android.graphics.Bitmap)} callback}.
	 * <br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri             Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param targetImageSize Minimal size for {@link Bitmap} which will be returned in
	 *                        {@linkplain ImageLoadingListener#onLoadingComplete(String, android.view.View,
	 *                        android.graphics.Bitmap)} callback}. Downloaded image will be decoded
	 *                        and scaled to {@link Bitmap} of the size which is <b>equal or larger</b> (usually a bit
	 *                        larger) than incoming targetImageSize.
	 * @param options         {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                        decoding and displaying. If <b>null</b> - default display image options
	 *                        {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions)
	 *                        from configuration} will be used.<br />
	 * @param listener        {@linkplain ImageLoadingListener Listener} for image loading process. Listener fires
	 *                        events on UI thread if this method is called on UI thread.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public void loadImage(String uri, ImageSize targetImageSize, DisplayImageOptions options,
			ImageLoadingListener listener) {
		loadImage(uri, targetImageSize, options, listener, null);
	}

	/**
	 * 添加一个图片加载任务到执行池中，这里并不会自动展示图片。
	 * 而是会通过ImageLoadingListener的onLoadingComplete进行回调。
	 *
	 * @param targetImageSize 图片想要的目标大小，在一般的任务当中后续会进行压缩等处理，
	 *                        最后再通过ImageLoadingListener的onLoadingComplete进行回调。
	 *                        可以为null，则默认为当前屏幕的宽高
	 *
	 * ...重复的参数见displayImage
	 */
	public void loadImage(String uri, ImageSize targetImageSize, DisplayImageOptions options,
			ImageLoadingListener listener, ImageLoadingProgressListener progressListener) {
		checkConfiguration();//ImageLoader必须设置全局配置才可以使用
		if (targetImageSize == null) {
			//在全局配置当中可以通过设置memoryCacheExtraOptions指定图片的大小
			//如果还不指定，则默认使用屏幕的宽高
			targetImageSize = configuration.getMaxImageSize();
		}
		if (options == null) {
			options = configuration.defaultDisplayImageOptions;
		}
		//内部基本就是一个空实现，不会进行setImageDrawable等操作
		NonViewAware imageAware = new NonViewAware(uri, targetImageSize, ViewScaleType.CROP);
		//回到平时使用的加载图片的方法，但是因为一个空载体的原因，会导致请求图片成功，最多进行缓存，但不会自动显示
		//可能说比较常用的就是预加载到缓存中的功能吧
		displayImage(uri, imageAware, options, listener, progressListener);
	}

	/**
	 * Loads and decodes image synchronously.<br />
	 * Default display image options
	 * {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions) from
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @return Result image Bitmap. Can be <b>null</b> if image loading/decoding was failed or cancelled.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public Bitmap loadImageSync(String uri) {
		return loadImageSync(uri, null, null);
	}

	/**
	 * Loads and decodes image synchronously.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri     Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param options {@linkplain com.nostra13.universalimageloader.core.DisplayImageOptions Options} for image
	 *                decoding and scaling. If <b>null</b> - default display image options
	 *                {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions) from
	 *                configuration} will be used.
	 * @return Result image Bitmap. Can be <b>null</b> if image loading/decoding was failed or cancelled.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public Bitmap loadImageSync(String uri, DisplayImageOptions options) {
		return loadImageSync(uri, null, options);
	}

	/**
	 * Loads and decodes image synchronously.<br />
	 * Default display image options
	 * {@linkplain ImageLoaderConfiguration.Builder#defaultDisplayImageOptions(DisplayImageOptions) from
	 * configuration} will be used.<br />
	 * <b>NOTE:</b> {@link #init(ImageLoaderConfiguration)} method must be called before this method call
	 *
	 * @param uri             Image URI (i.e. "http://site.com/image.png", "file:///mnt/sdcard/image.png")
	 * @param targetImageSize Minimal size for {@link Bitmap} which will be returned. Downloaded image will be decoded
	 *                        and scaled to {@link Bitmap} of the size which is <b>equal or larger</b> (usually a bit
	 *                        larger) than incoming targetImageSize.
	 * @return Result image Bitmap. Can be <b>null</b> if image loading/decoding was failed or cancelled.
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public Bitmap loadImageSync(String uri, ImageSize targetImageSize) {
		return loadImageSync(uri, targetImageSize, null);
	}

	/**
	 * 同步的加载图片方法
	 * 该方法最好在子线程中使用，因为这个过程中可能会有从网络上加载图片的过程
	 *
	 * ...参数可以见displayImage或者loadImage
	 *
	 * @return 图片的加载结果，即Bitmap。可以为null，意味着加载图片失败
	 */
	public Bitmap loadImageSync(String uri, ImageSize targetImageSize, DisplayImageOptions options) {
		if (options == null) {
			options = configuration.defaultDisplayImageOptions;
		}
		//复制一份DisplayImageOptions并且设置isSyncLoading标志
		//这个在displayImage中可以看到实际上要求在当前调用线程中执行图片的加载过程
		options = new DisplayImageOptions.Builder().cloneFrom(options).syncLoading(true).build();
		//该回调会在onLoadingComplete记录最后获得的bitmap
		//然后可以通过getLoadedBitmap获得对应的bitmap
		SyncImageLoadingListener listener = new SyncImageLoadingListener();
		//这个方法最后也是走displayImage，主要是不展示图片，然后将结果回调到SyncImageLoadingListener中
		loadImage(uri, targetImageSize, options, listener);
		//获得加载的结果，可能为null
		//但是要注意，因为请求图片可能会从网络上获取，又是同步方法，则该方法最好在子线程中使用
		//除非你能确定图片能从内存中获取，否则都是在子线程中合适
		return listener.getLoadedBitmap();
	}

	/**
	 * Checks if ImageLoader's configuration was initialized
	 *
	 * @throws IllegalStateException if configuration wasn't initialized
	 */
	private void checkConfiguration() {
		if (configuration == null) {
			throw new IllegalStateException(ERROR_NOT_INIT);
		}
	}

	/**
	 * Returns memory cache
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public MemoryCache getMemoryCache() {
		checkConfiguration();
		return configuration.memoryCache;
	}

	/**
	 * Returns disk cache
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @deprecated Use {@link #getDiskCache()} instead
	 */
	@Deprecated
	public DiskCache getDiscCache() {
		return getDiskCache();
	}

	/**
	 * Returns disk cache
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 */
	public DiskCache getDiskCache() {
		checkConfiguration();
		return configuration.diskCache;
	}

	/**
	 * Clears disk cache.
	 *
	 * @throws IllegalStateException if {@link #init(ImageLoaderConfiguration)} method wasn't called before
	 * @deprecated Use {@link #clearDiskCache()} instead
	 */
	@Deprecated
	public void clearDiscCache() {
		clearDiskCache();
	}

	/**
	 * Returns URI of image which is loading at this moment into passed
	 * {@link com.nostra13.universalimageloader.core.imageaware.ImageAware ImageAware}
	 */
	public String getLoadingUriForView(ImageAware imageAware) {
		return engine.getLoadingUriForView(imageAware);
	}

	/**
	 * Returns URI of image which is loading at this moment into passed
	 * {@link android.widget.ImageView ImageView}
	 */
	public String getLoadingUriForView(ImageView imageView) {
		return engine.getLoadingUriForView(new ImageViewAware(imageView));
	}

	/**
	 * Cancel the task of loading and displaying image for passed
	 * {@link android.widget.ImageView ImageView}.
	 *
	 * @param imageView {@link android.widget.ImageView ImageView} for which display task will be cancelled
	 */
	public void cancelDisplayTask(ImageView imageView) {
		engine.cancelDisplayTaskFor(new ImageViewAware(imageView));
	}

	/**
	 * 清空硬盘缓存
	 */
	public void clearDiskCache() {
		checkConfiguration();
		configuration.diskCache.clear();
	}

	/**
	 * 清空内存缓存
	 */
	public void clearMemoryCache() {
		checkConfiguration();
		configuration.memoryCache.clear();
	}

	/**
	 * 设置一个所有图片请求的默认请求监听
	 * 当请求图片的时候listener为null的时候默认使用
	 * */
	public void setDefaultLoadingListener(ImageLoadingListener listener) {
		defaultListener = listener == null ? new SimpleImageLoadingListener() : listener;
	}

	/**
	 * 尝试取消当前的展示任务
	 *
	 * @param imageAware 当前需要取消的请求对应的载体
	 */
	public void cancelDisplayTask(ImageAware imageAware) {
		engine.cancelDisplayTaskFor(imageAware);
	}

	/**
	 * 拒绝或允许从网络上下载图片
	 * <br />
	 * 如果网络被拒绝，那么图片不会进行缓存，然后会进行ImageLoadingListener的onLoadingFailed回调
	 * 并且指示FailReason为FailReason.FailType#NETWORK_DENIED
	 *
	 * @param denyNetworkDownloads true的话拒绝从网络上获取图片
	 * note:注意这个如果在图片已经获取ImageDownloader之后当次就无效了，只有之后的才会生效
	 */
	public void denyNetworkDownloads(boolean denyNetworkDownloads) {
		engine.denyNetworkDownloads(denyNetworkDownloads);
	}

	/**
	 * 设置当前网络偏慢，http和https的请求将采用FlushedInputStream进行处理
	 *
	 * @param handleSlowNetwork true的话会使用FlushedInputStream处理网络的图片流
	 */
	public void handleSlowNetwork(boolean handleSlowNetwork) {
		engine.handleSlowNetwork(handleSlowNetwork);
	}

	/**
	 * 暂停ImageLoader，后续需要执行的任务只有当resume()之后才会执行
	 */
	public void pause() {
		engine.pause();
	}

	/**
	 * 尝试恢复那些暂停的任务，对应pause()
	 * */
	public void resume() {
		engine.resume();
	}

	/**
	 * 会尝试取消所有运行中或者等待运行的任务
	 * 注意如果使用的是自定义的线程池，这个方法会导致自定义线程池shutdown
	 * 但是后续ImageLoader依然可以使用，不过会使用ImageLoader默认的线程池
	 */
	public void stop() {
		engine.stop();
	}

	/**
	 * stop当前ImageLoader，并且清空配置
	 * 后续可以通过重新init来重新使用ImageLoader
	 */
	public void destroy() {
		if (configuration != null) L.d(LOG_DESTROY);
		stop();
		configuration.diskCache.close();
		engine = null;
		configuration = null;
	}

	private static Handler defineHandler(DisplayImageOptions options) {
		Handler handler = options.getHandler();
		if (options.isSyncLoading()) {//同步加载，这个handler是没有用的，直接置空就好
			handler = null;
		} else if (handler == null && Looper.myLooper() == Looper.getMainLooper()) {//如果DisplayOption中没有设置Handler，并且当前是主线程，使用主线程Handler
			handler = new Handler();//主线程Handler
		}
		//当异步加载的时候，如果设置了DisplayOption中的Handler（在这种场景下，一般这种Handler都是要在子线程的，不然没什么意义），则使用，否则为null
		return handler;
	}

	/**
	 * Listener which is designed for synchronous image loading.
	 *
	 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
	 * @since 1.9.0
	 */
	private static class SyncImageLoadingListener extends SimpleImageLoadingListener {

		private Bitmap loadedImage;

		@Override
		public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
			this.loadedImage = loadedImage;
		}

		public Bitmap getLoadedBitmap() {
			return loadedImage;
		}
	}
}
