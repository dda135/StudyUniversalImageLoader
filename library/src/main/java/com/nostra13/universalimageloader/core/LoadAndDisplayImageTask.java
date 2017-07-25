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
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.FailReason.FailType;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.assist.LoadedFrom;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;
import com.nostra13.universalimageloader.core.decode.ImageDecoder;
import com.nostra13.universalimageloader.core.decode.ImageDecodingInfo;
import com.nostra13.universalimageloader.core.download.ImageDownloader;
import com.nostra13.universalimageloader.core.download.ImageDownloader.Scheme;
import com.nostra13.universalimageloader.core.imageaware.ImageAware;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener;
import com.nostra13.universalimageloader.utils.IoUtils;
import com.nostra13.universalimageloader.utils.L;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Presents load'n'display image task. Used to load image from Internet or file system, decode it to {@link Bitmap}, and
 * display it in {@link com.nostra13.universalimageloader.core.imageaware.ImageAware} using {@link DisplayBitmapTask}.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @see ImageLoaderConfiguration
 * @see ImageLoadingInfo
 * @since 1.3.1
 */
final class LoadAndDisplayImageTask implements Runnable, IoUtils.CopyListener {

	private static final String LOG_WAITING_FOR_RESUME = "ImageLoader is paused. Waiting...  [%s]";
	private static final String LOG_RESUME_AFTER_PAUSE = ".. Resume loading [%s]";
	private static final String LOG_DELAY_BEFORE_LOADING = "Delay %d ms before loading...  [%s]";
	private static final String LOG_START_DISPLAY_IMAGE_TASK = "Start display image task [%s]";
	private static final String LOG_WAITING_FOR_IMAGE_LOADED = "Image already is loading. Waiting... [%s]";
	private static final String LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING = "...Get cached bitmap from memory after waiting. [%s]";
	private static final String LOG_LOAD_IMAGE_FROM_NETWORK = "Load image from network [%s]";
	private static final String LOG_LOAD_IMAGE_FROM_DISK_CACHE = "Load image from disk cache [%s]";
	private static final String LOG_RESIZE_CACHED_IMAGE_FILE = "Resize image in disk cache [%s]";
	private static final String LOG_PREPROCESS_IMAGE = "PreProcess image before caching in memory [%s]";
	private static final String LOG_POSTPROCESS_IMAGE = "PostProcess image before displaying [%s]";
	private static final String LOG_CACHE_IMAGE_IN_MEMORY = "Cache image in memory [%s]";
	private static final String LOG_CACHE_IMAGE_ON_DISK = "Cache image on disk [%s]";
	private static final String LOG_PROCESS_IMAGE_BEFORE_CACHE_ON_DISK = "Process image before cache on disk [%s]";
	private static final String LOG_TASK_CANCELLED_IMAGEAWARE_REUSED = "ImageAware is reused for another image. Task is cancelled. [%s]";
	private static final String LOG_TASK_CANCELLED_IMAGEAWARE_COLLECTED = "ImageAware was collected by GC. Task is cancelled. [%s]";
	private static final String LOG_TASK_INTERRUPTED = "Task was interrupted [%s]";

	private static final String ERROR_NO_IMAGE_STREAM = "No stream for image [%s]";
	private static final String ERROR_PRE_PROCESSOR_NULL = "Pre-processor returned null [%s]";
	private static final String ERROR_POST_PROCESSOR_NULL = "Post-processor returned null [%s]";
	private static final String ERROR_PROCESSOR_FOR_DISK_CACHE_NULL = "Bitmap processor for disk cache returned null [%s]";

	private final ImageLoaderEngine engine;
	private final ImageLoadingInfo imageLoadingInfo;
	private final Handler handler;

	// Helper references
	private final ImageLoaderConfiguration configuration;
	private final ImageDownloader downloader;
	private final ImageDownloader networkDeniedDownloader;
	private final ImageDownloader slowNetworkDownloader;
	private final ImageDecoder decoder;
	final String uri;
	private final String memoryCacheKey;
	final ImageAware imageAware;
	private final ImageSize targetSize;
	final DisplayImageOptions options;
	final ImageLoadingListener listener;
	final ImageLoadingProgressListener progressListener;
	private final boolean syncLoading;

	// State vars
	private LoadedFrom loadedFrom = LoadedFrom.NETWORK;

	public LoadAndDisplayImageTask(ImageLoaderEngine engine, ImageLoadingInfo imageLoadingInfo, Handler handler) {
		this.engine = engine;
		this.imageLoadingInfo = imageLoadingInfo;
		this.handler = handler;

		configuration = engine.configuration;
		downloader = configuration.downloader;
		networkDeniedDownloader = configuration.networkDeniedDownloader;
		slowNetworkDownloader = configuration.slowNetworkDownloader;
		decoder = configuration.decoder;
		uri = imageLoadingInfo.uri;
		memoryCacheKey = imageLoadingInfo.memoryCacheKey;
		imageAware = imageLoadingInfo.imageAware;
		targetSize = imageLoadingInfo.targetSize;
		options = imageLoadingInfo.options;
		listener = imageLoadingInfo.listener;
		progressListener = imageLoadingInfo.progressListener;
		syncLoading = options.isSyncLoading();
	}

	@Override
	public void run() {
		//ImageLoaderEngine是否被暂停，如果暂停，则当前任务直接结束
		//不过还是可以从内存缓存中获取图片，这个在列表页滑动加载还是很有意义的
		//因为线程池默认是fixed类型的线程池，那么pause的时候最多会导致核心线程数的等待
		//后续任务进入都会进行线程池的队列中等待执行
		if (waitIfPaused()) return;
		//如果配置了当前任务需要延时读取，则当前线程会沉睡指定毫秒后唤醒，如果任务有效则继续执行
		if (delayIfNeed()) return;
		//这个是当前链接对应的线程锁
		//一个连接对应有一个ReentrantLock
		ReentrantLock loadFromUriLock = imageLoadingInfo.loadFromUriLock;
		L.d(LOG_START_DISPLAY_IMAGE_TASK, memoryCacheKey);
		if (loadFromUriLock.isLocked()) {
			L.d(LOG_WAITING_FOR_IMAGE_LOADED, memoryCacheKey);
		}
		//假设此时对于同一个链接不同的载体的不同的线程中的任务
		//如果已经发起过一个请求，则该链接的请求将会加锁，之后如果有重复的请求发生，会一直等待直到锁释放
		//同一个链接的请求同一时刻只能有一个
		//等第一个请求完成之后，可能在硬盘缓存或者内存缓存中存在缓存
		//从而后续的操作就可以从缓存中获取图片再进行操作
		loadFromUriLock.lock();
		Bitmap bmp;
		try {
			//对于同一个链接的任务，有的任务在等待后重新执行，此时可能过了一段时间
			//需要检查任务的有效性，如果无效直接进入catch
			checkTaskNotActual();
			//再次尝试从内存缓存中获取
			//主要场景就是当多个相同链接的请求发生的时候，只有一个任务正常执行，其余任务阻塞
			//那么当第一个任务完成之后，下一个任务唤醒之后，此时可能因为第一个任务的成功而导致内存缓存中有值
			//此时从内存缓存中获取即可，不必要再次进行多余操作
			bmp = configuration.memoryCache.get(memoryCacheKey);
			if (bmp == null || bmp.isRecycled()) {//内存缓存中还是没有数据
				//从硬盘或者网络上尝试获取Bitmap
				bmp = tryLoadBitmap();
				if (bmp == null) return; // listener callback already was fired
				//bitmap在缓存到内存缓存中之前可能要进行preProcessor操作
				//在这之前先检查任务的有效性
				checkTaskNotActual();
				checkTaskInterrupted();
				//后续操作和硬盘缓存已经没有关系

				//在进行内存缓存前可以对Bitmap进行操作
				//这个对应于从内存缓存中获取bitmap之后的postProcessor
				if (options.shouldPreProcess()) {
					L.d(LOG_PREPROCESS_IMAGE, memoryCacheKey);
					bmp = options.getPreProcessor().process(bmp);
					if (bmp == null) {
						L.e(ERROR_PRE_PROCESSOR_NULL, memoryCacheKey);
					}
				}
				//当前允许将bitmap存入内存缓存中
				if (bmp != null && options.isCacheInMemory()) {
					L.d(LOG_CACHE_IMAGE_IN_MEMORY, memoryCacheKey);
					//进行内存缓存
					configuration.memoryCache.put(memoryCacheKey, bmp);
				}
			} else {
				loadedFrom = LoadedFrom.MEMORY_CACHE;
				L.d(LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING, memoryCacheKey);
			}
			//这里的bitmap可以看做从内存缓存中获取的，
			if (bmp != null && options.shouldPostProcess()) {
				L.d(LOG_POSTPROCESS_IMAGE, memoryCacheKey);
				bmp = options.getPostProcessor().process(bmp);
				if (bmp == null) {
					L.e(ERROR_POST_PROCESSOR_NULL, memoryCacheKey);
				}
			}
			//在进行展示任务之前，检查任务的有效性
			checkTaskNotActual();
			checkTaskInterrupted();
		} catch (TaskCancelledException e) {
			fireCancelEvent();//这个异常仅对应与任务取消异常，会回调onLoadingCancelled
			return;
		} finally {//注意ReentrantLock的基础，必须在try/catch/finally模块中，否则可能出现锁无法释放的情况
			loadFromUriLock.unlock();
		}
		//进行展示任务
		DisplayBitmapTask displayBitmapTask = new DisplayBitmapTask(bmp, imageLoadingInfo, engine, loadedFrom);
		runTask(displayBitmapTask, syncLoading, handler, engine);
	}

	/**
	 * 如果有必要，wait当前线程
	 * */
	private boolean waitIfPaused() {
		AtomicBoolean pause = engine.getPause();
		if (pause.get()) {//获取当前线程池是否暂停的标志
			//如果当前线程池被暂停
			synchronized (engine.getPauseLock()) {//暂停锁
				if (pause.get()) {//因为锁的原因，可能会导致阻塞了一些时间，这里需要进行二次检查
					L.d(LOG_WAITING_FOR_RESUME, memoryCacheKey);
					try {
						//如果当前线程池需要暂停，释放engine.getPauseLock()的锁，并且当前线程等待执行
						//ImageLoaderEngine在resume中通过engine.getPauseLock().notifyAll即可唤醒当前所有在等待中的线程
						engine.getPauseLock().wait();
					} catch (InterruptedException e) {
						L.e(LOG_TASK_INTERRUPTED, memoryCacheKey);
						return true;
					}
					L.d(LOG_RESUME_AFTER_PAUSE, memoryCacheKey);
				}
			}
		}
		//当任务被唤醒时，因为可能隔了一段时间，重新检查一下当前任务的有效性
		return isTaskNotActual();
	}

	/**
	 * 如果需要，尝试延时当前线程的执行
	 * */
	private boolean delayIfNeed() {
		//如果在DisplayOption中配置了延时读取的毫秒
		if (options.shouldDelayBeforeLoading()) {
			L.d(LOG_DELAY_BEFORE_LOADING, options.getDelayBeforeLoading(), memoryCacheKey);
			try {//线程沉睡指定毫秒
				Thread.sleep(options.getDelayBeforeLoading());
			} catch (InterruptedException e) {
				L.e(LOG_TASK_INTERRUPTED, memoryCacheKey);
				return true;
			}
			//唤醒后，因为过了一段时间，需要重新检查当前任务的有效性
			return isTaskNotActual();
		}
		return false;
	}

	/**
	 * 尝试从硬盘和网络上获取Bitmap
	 * @return 获取的bitmap
	 * @throws TaskCancelledException 当前任务已经无效异常
     */
	private Bitmap tryLoadBitmap() throws TaskCancelledException {
		Bitmap bitmap = null;
		try {
			//从硬盘缓存中根据链接获取指定文件
			File imageFile = configuration.diskCache.get(uri);
			//该文件是可读性质的，同时要求长度>0
			if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {//击中硬盘缓存
				L.d(LOG_LOAD_IMAGE_FROM_DISK_CACHE, memoryCacheKey);
				loadedFrom = LoadedFrom.DISC_CACHE;
				//即将进行图片的压缩等处理，先检查任务的有效性
				checkTaskNotActual();
				//根据uri解析bitmap，这个Scheme中定义了ImageLoader可以识别的前缀，具体看Scheme类
				bitmap = decodeImage(Scheme.FILE.wrap(imageFile.getAbsolutePath()));
			}
			//未击中硬盘缓存
			if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
				L.d(LOG_LOAD_IMAGE_FROM_NETWORK, memoryCacheKey);
				loadedFrom = LoadedFrom.NETWORK;

				String imageUriForDecoding = uri;
				//DisplayOptions中设置允许缓存在硬盘中的话，尝试从网络上加载图片并且缓存在硬盘中
				if (options.isCacheOnDisk() && tryCacheImageOnDisk()) {
					//尝试从硬盘中获取刚刚通过网络等方式获取的图片
					//这里除非在ImageLoaderConfiguration中指定硬盘缓存中的最大宽高
					//否则一般来说就是原图
					imageFile = configuration.diskCache.get(uri);
					if (imageFile != null) {
						//获取成功后需要添加file的Scheme
						imageUriForDecoding = Scheme.FILE.wrap(imageFile.getAbsolutePath());
					}
				}
				//准备进行拉伸压缩等操作，先检查任务的有效性
				checkTaskNotActual();
				//如果允许硬盘缓存的话，再次解析文件，压缩等操作（之前进行过压缩，所以这里基本上就是过一遍判断）
				//否则就是从网络上获取流，然后压缩等操作，不会进行硬盘缓存
				bitmap = decodeImage(imageUriForDecoding);

				if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
					fireFailEvent(FailType.DECODING_ERROR, null);
				}
			}
			//普通的异常意味着获取图片失败，会设置fail时候设置的图片，并且回调onLoadingFailed
			//有一个异常比较特殊，任务取消异常，这个会在上一级catch中捕捉
		} catch (IllegalStateException e) {
			fireFailEvent(FailType.NETWORK_DENIED, null);
		} catch (TaskCancelledException e) {
			throw e;
		} catch (IOException e) {
			L.e(e);
			fireFailEvent(FailType.IO_ERROR, e);
		} catch (OutOfMemoryError e) {
			L.e(e);
			fireFailEvent(FailType.OUT_OF_MEMORY, e);
		} catch (Throwable e) {
			L.e(e);
			fireFailEvent(FailType.UNKNOWN, e);
		}
		return bitmap;
	}

	/**
	 * 根据uri解析图片（uri可能是http、drawable、content等，具体看Scheme类）
	 * @param imageUri 当前要处理的图片URI
	 * @return 压缩拉伸等处理后的Bitmap
     */
	private Bitmap decodeImage(String imageUri) throws IOException {
		//获取压缩类型CROP或FIT_INSIDE
		ViewScaleType viewScaleType = imageAware.getScaleType();
		//创建解析Bitmap所需要的参数
		ImageDecodingInfo decodingInfo = new ImageDecodingInfo(memoryCacheKey, imageUri, uri, targetSize, viewScaleType,
				getDownloader(), options);
		//默认decoder在ImageLoaderConfiguration中创建BaseImageDecoder
		return decoder.decode(decodingInfo);
	}

	/**
	 * 尝试将图片缓存到硬盘中
	 * */
	private boolean tryCacheImageOnDisk() throws TaskCancelledException {
		L.d(LOG_CACHE_IMAGE_ON_DISK, memoryCacheKey);

		boolean loaded;
		try {
			//从网络上加载图片，并且缓存进硬盘当中
			loaded = downloadImage();
			if (loaded) {//缓存成功
				//如果配置当中设置了硬盘缓存的最大宽高，则需要改变bitmap的宽高并且覆盖缓存
				int width = configuration.maxImageWidthForDiskCache;
				int height = configuration.maxImageHeightForDiskCache;
				if (width > 0 || height > 0) {//注意这里的resize会影响到内存缓存的结果，因为内存缓存是在最后面操作
					L.d(LOG_RESIZE_CACHED_IMAGE_FILE, memoryCacheKey);
					resizeAndSaveImage(width, height); // TODO : process boolean result
				}
			}
			//默认硬盘缓存中是原图
		} catch (IOException e) {
			L.e(e);
			loaded = false;
		}
		return loaded;
	}

	/**
	 * 从网络（或者文件之类）上获取图片的输入流，并且保存进硬盘当中
	 * @return true表示硬盘缓存成功
     */
	private boolean downloadImage() throws IOException {
		//从指定来源获取图片的输入流
		InputStream is = getDownloader().getStream(uri, options.getExtraForDownloader());
		if (is == null) {
			L.e(ERROR_NO_IMAGE_STREAM, memoryCacheKey);
			return false;
		} else {
			try {//将图片缓存到硬盘中，这里明显是原图
				return configuration.diskCache.save(uri, is, this);
			} finally {
				IoUtils.closeSilently(is);
			}
		}
	}

	/**
	 * 将硬盘缓存中的数据重新设置宽高，然后覆盖
	 * 用于设置硬盘缓存中的最大宽高
	 * */
	private boolean resizeAndSaveImage(int maxWidth, int maxHeight) throws IOException {
		boolean saved = false;

		File targetFile = configuration.diskCache.get(uri);
		//从网络或者其它来源加载图片输入流成功之后，会先将流存入硬盘缓存中
		//这里会尝试再次取出
		if (targetFile != null && targetFile.exists()) {
			ImageSize targetImageSize = new ImageSize(maxWidth, maxHeight);
			//指定IN_SAMPLE_INT的时候，只会进行压缩处理，不会拉伸
			DisplayImageOptions specialOptions = new DisplayImageOptions.Builder().cloneFrom(options)
					.imageScaleType(ImageScaleType.IN_SAMPLE_INT).build();
			ImageDecodingInfo decodingInfo = new ImageDecodingInfo(memoryCacheKey,
					Scheme.FILE.wrap(targetFile.getAbsolutePath()), uri, targetImageSize, ViewScaleType.FIT_INSIDE,
					getDownloader(), specialOptions);
			//根据给定的新的宽高重新拉伸压缩等操作
			Bitmap bmp = decoder.decode(decodingInfo);
			if (bmp != null && configuration.processorForDiskCache != null) {
				L.d(LOG_PROCESS_IMAGE_BEFORE_CACHE_ON_DISK, memoryCacheKey);
				bmp = configuration.processorForDiskCache.process(bmp);
				if (bmp == null) {
					L.e(ERROR_PROCESSOR_FOR_DISK_CACHE_NULL, memoryCacheKey);
				}
			}
			if (bmp != null) {//将处理后的bitmap重新写入硬盘缓存中进行覆盖
				saved = configuration.diskCache.save(uri, bmp);
				bmp.recycle();
			}
		}
		return saved;
	}

	@Override
	public boolean onBytesCopied(int current, int total) {
		return syncLoading || fireProgressEvent(current, total);
	}

	/** @return <b>true</b> - if loading should be continued; <b>false</b> - if loading should be interrupted */
	private boolean fireProgressEvent(final int current, final int total) {
		if (isTaskInterrupted() || isTaskNotActual()) return false;
		if (progressListener != null) {
			Runnable r = new Runnable() {
				@Override
				public void run() {
					progressListener.onProgressUpdate(uri, imageAware.getWrappedView(), current, total);
				}
			};
			runTask(r, false, handler, engine);
		}
		return true;
	}

	/**
	 * 回调加载图片失败
     */
	private void fireFailEvent(final FailType failType, final Throwable failCause) {
		if (syncLoading || isTaskInterrupted() || isTaskNotActual()) return;
		Runnable r = new Runnable() {
			@Override
			public void run() {
				if (options.shouldShowImageOnFail()) {
					imageAware.setImageDrawable(options.getImageOnFail(configuration.resources));
				}
				listener.onLoadingFailed(uri, imageAware.getWrappedView(), new FailReason(failType, failCause));
			}
		};
		runTask(r, false, handler, engine);
	}

	private void fireCancelEvent() {
		if (syncLoading || isTaskInterrupted()) return;
		Runnable r = new Runnable() {
			@Override
			public void run() {
				listener.onLoadingCancelled(uri, imageAware.getWrappedView());
			}
		};
		runTask(r, false, handler, engine);
	}

	/**
	 * 获取图片加载实现类
     */
	private ImageDownloader getDownloader() {
		ImageDownloader d;
		//具体的定义都是在ImageLoaderConfiguration里面
		if (engine.isNetworkDenied()) {//拒绝从网络上下载图片NetworkDeniedImageDownloader
			d = networkDeniedDownloader;
		} else if (engine.isSlowNetwork()) {//当前网络偏慢SlowNetworkImageDownloader
			d = slowNetworkDownloader;
		} else {//一般使用的BaseImageDownloader
			d = downloader;
		}
		return d;
	}

	/**
	 * @throws TaskCancelledException if task is not actual (target ImageAware is collected by GC or the image URI of
	 *                                this task doesn't match to image URI which is actual for current ImageAware at
	 *                                this moment)
	 */
	private void checkTaskNotActual() throws TaskCancelledException {
		checkViewCollected();
		checkViewReused();
	}

	/**
	 * @return <b>true</b> - if task is not actual (target ImageAware is collected by GC or the image URI of this task
	 * doesn't match to image URI which is actual for current ImageAware at this moment)); <b>false</b> - otherwise
	 */
	private boolean isTaskNotActual() {
		//当前任务是否有效
		//1.载体是否被回收
		//2.当前载体已经开启了新的任务
		return isViewCollected() || isViewReused();
	}

	/** @throws TaskCancelledException if target ImageAware is collected */
	private void checkViewCollected() throws TaskCancelledException {
		if (isViewCollected()) {
			throw new TaskCancelledException();
		}
	}

	/** @return <b>true</b> - if target ImageAware is collected by GC; <b>false</b> - otherwise */
	private boolean isViewCollected() {
		if (imageAware.isCollected()) {
			L.d(LOG_TASK_CANCELLED_IMAGEAWARE_COLLECTED, memoryCacheKey);
			return true;
		}
		return false;
	}

	/** @throws TaskCancelledException if target ImageAware is collected by GC */
	private void checkViewReused() throws TaskCancelledException {
		if (isViewReused()) {
			throw new TaskCancelledException();
		}
	}

	/** @return <b>true</b> - if current ImageAware is reused for displaying another image; <b>false</b> - otherwise */
	private boolean isViewReused() {
		String currentCacheKey = engine.getLoadingUriForView(imageAware);
		// Check whether memory cache key (image URI) for current ImageAware is actual.
		// If ImageAware is reused for another task then current task should be cancelled.
		boolean imageAwareWasReused = !memoryCacheKey.equals(currentCacheKey);
		if (imageAwareWasReused) {
			L.d(LOG_TASK_CANCELLED_IMAGEAWARE_REUSED, memoryCacheKey);
			return true;
		}
		return false;
	}

	/** @throws TaskCancelledException if current task was interrupted */
	private void checkTaskInterrupted() throws TaskCancelledException {
		if (isTaskInterrupted()) {
			throw new TaskCancelledException();
		}
	}

	/** @return <b>true</b> - if current task was interrupted; <b>false</b> - otherwise */
	private boolean isTaskInterrupted() {
		if (Thread.interrupted()) {
			L.d(LOG_TASK_INTERRUPTED, memoryCacheKey);
			return true;
		}
		return false;
	}

	String getLoadingUri() {
		return uri;
	}

	static void runTask(Runnable r, boolean sync, Handler handler, ImageLoaderEngine engine) {
		if (sync) {//如果同步进行，直接在当前线程执行
			r.run();
		} else if (handler == null) {//异步执行，但是没有指定handler，在线程池的子线程中执行
			engine.fireCallback(r);
		} else {//异步执行，但是同时指定了handler，还是在handler中执行
			handler.post(r);
		}
	}

	/**
	 * Exceptions for case when task is cancelled (thread is interrupted, image view is reused for another task, view is
	 * collected by GC).
	 *
	 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
	 * @since 1.9.1
	 */
	class TaskCancelledException extends Exception {
	}
}
