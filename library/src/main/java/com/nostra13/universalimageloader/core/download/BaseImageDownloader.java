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
package com.nostra13.universalimageloader.core.download;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.assist.ContentLengthInputStream;
import com.nostra13.universalimageloader.utils.IoUtils;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/**
 * Provides retrieving of {@link InputStream} of image by URI from network or file system or app resources.<br />
 * {@link URLConnection} is used to retrieve image stream from network.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.8.0
 */
public class BaseImageDownloader implements ImageDownloader {
	/** {@value} 默认5秒的链接超时*/
	public static final int DEFAULT_HTTP_CONNECT_TIMEOUT = 5 * 1000; // milliseconds
	/** {@value} 默认20秒的流读取超时*/
	public static final int DEFAULT_HTTP_READ_TIMEOUT = 20 * 1000; // milliseconds

	/** {@value} */
	protected static final int BUFFER_SIZE = 32 * 1024; // 32 Kb
	/** {@value} */
	protected static final String ALLOWED_URI_CHARS = "@#&=*+-_.,:!?()/~'%";

	protected static final int MAX_REDIRECT_COUNT = 5;

	protected static final String CONTENT_CONTACTS_URI_PREFIX = "content://com.android.contacts/";

	private static final String ERROR_UNSUPPORTED_SCHEME = "UIL doesn't support scheme(protocol) by default [%s]. " + "You should implement this support yourself (BaseImageDownloader.getStreamFromOtherSource(...))";

	protected final Context context;
	protected final int connectTimeout;
	protected final int readTimeout;

	public BaseImageDownloader(Context context) {
		this(context, DEFAULT_HTTP_CONNECT_TIMEOUT, DEFAULT_HTTP_READ_TIMEOUT);
	}

	public BaseImageDownloader(Context context, int connectTimeout, int readTimeout) {
		this.context = context.getApplicationContext();
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
	}

	@Override
	public InputStream getStream(String imageUri, Object extra) throws IOException {
		//根据不同的Scheme获取对应的流，一个个看
		switch (Scheme.ofUri(imageUri)) {
			case HTTP:
			case HTTPS:
				//网络
				return getStreamFromNetwork(imageUri, extra);
			case FILE:
				//文件，主要用于硬盘缓存，当然也可以自己写文件路径
				return getStreamFromFile(imageUri, extra);
			case CONTENT:
				//内容提供者，当然就包括一些文件
				return getStreamFromContent(imageUri, extra);
			case ASSETS:
				//assets中的资源
				return getStreamFromAssets(imageUri, extra);
			case DRAWABLE:
				//res中的图片资源
				return getStreamFromDrawable(imageUri, extra);
			case UNKNOWN:
			default:
				//异常处理，直接抛出异常
				return getStreamFromOtherSource(imageUri, extra);
		}
	}

	/**
	 * Retrieves {@link InputStream} of image by URI (image is located in the network).
	 * 从网络上获取对应输入流
	 * @param imageUri Image URI
	 * @param extra    Auxiliary object which was passed to {@link DisplayImageOptions.Builder#extraForDownloader(Object)
	 *                 DisplayImageOptions.extraForDownloader(Object)}; can be null
	 * @return {@link InputStream} of image
	 * @throws IOException if some I/O error occurs during network request or if no InputStream could be created for
	 *                     URL.
	 */
	protected InputStream getStreamFromNetwork(String imageUri, Object extra) throws IOException {
		HttpURLConnection conn = createConnection(imageUri, extra);

		int redirectCount = 0;
		//状态码如果是300-399的状态，认为当前链接存在重定向的行为，此时重定向之后会将新的地址放入报头的Location中
		//之所以最多5次，是因为HttpURLConnection内部最多允许5次重定向http://blog.csdn.net/flowingflying/article/details/18731667
		while (conn.getResponseCode() / 100 == 3 && redirectCount < MAX_REDIRECT_COUNT) {
			conn = createConnection(conn.getHeaderField("Location"), extra);
			redirectCount++;
		}

		InputStream imageStream;
		try {
			//获取图片的输入流
			imageStream = conn.getInputStream();
		} catch (IOException e) {
			// Read all data to allow reuse connection (http://bit.ly/1ad35PY)
			//如果链接成功但是出现了异常，Connection仍然会返回一个错误流，此时为了继续使用Connection，最好将流全部读取并且关闭
			IoUtils.readAndCloseStream(conn.getErrorStream());
			throw e;
		}
		//判断当前返回标志是否为200
		if (!shouldBeProcessed(conn)) {//当前没有返回200，认为返回失败
			IoUtils.closeSilently(imageStream);//关闭当前输入流
			throw new IOException("Image request failed with response code " + conn.getResponseCode());
		}

		return new ContentLengthInputStream(new BufferedInputStream(imageStream, BUFFER_SIZE), conn.getContentLength());
	}

	/**
	 * HttpURLConnection返回200状态码才认为成功
	 * @param conn Opened request connection (response code is available)
	 * @return <b>true</b> - if data from connection is correct and should be read and processed;
	 *         <b>false</b> - if response contains irrelevant data and shouldn't be processed
	 * @throws IOException
	 */
	protected boolean shouldBeProcessed(HttpURLConnection conn) throws IOException {
		return conn.getResponseCode() == 200;
	}

	/**
	 * Create {@linkplain HttpURLConnection HTTP connection} for incoming URL
	 * 创建指定的HttpURLConnetion
	 * @param url   URL to connect to
	 * @param extra Auxiliary object which was passed to {@link DisplayImageOptions.Builder#extraForDownloader(Object)
	 *              DisplayImageOptions.extraForDownloader(Object)}; can be null
	 * @return {@linkplain HttpURLConnection Connection} for incoming URL. Connection isn't established so it still configurable.
	 * @throws IOException if some I/O error occurs during network request or if no InputStream could be created for
	 *                     URL.
	 */
	protected HttpURLConnection createConnection(String url, Object extra) throws IOException {
		//这里对链接进行了encode操作，当中指定了一些需要避免encode的字符
		String encodedUrl = Uri.encode(url, ALLOWED_URI_CHARS);
		HttpURLConnection conn = (HttpURLConnection) new URL(encodedUrl).openConnection();
		conn.setConnectTimeout(connectTimeout);
		conn.setReadTimeout(readTimeout);
		return conn;
	}

	/**
	 * 返回来源于文件的图片的输入流
	 * Retrieves {@link InputStream} of image by URI (image is located on the local file system or SD card).
	 *
	 * @param imageUri Image URI
	 * @param extra    Auxiliary object which was passed to {@link DisplayImageOptions.Builder#extraForDownloader(Object)
	 *                 DisplayImageOptions.extraForDownloader(Object)}; can be null
	 * @return {@link InputStream} of image
	 * @throws IOException if some I/O error occurs reading from file system
	 */
	protected InputStream getStreamFromFile(String imageUri, Object extra) throws IOException {
		//获取文件的绝对路径（不包含Scheme）
		String filePath = Scheme.FILE.crop(imageUri);
		if (isVideoFileUri(imageUri)) {
			//如果是video类型文件，获取其缩略图
			return getVideoThumbnailStream(filePath);
		} else {//普通的文件
			BufferedInputStream imageStream = new BufferedInputStream(new FileInputStream(filePath), BUFFER_SIZE);
			return new ContentLengthInputStream(imageStream, (int) new File(filePath).length());
		}
	}

	@TargetApi(Build.VERSION_CODES.FROYO)
	private InputStream getVideoThumbnailStream(String filePath) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {
			//获取缩略图
			Bitmap bitmap = ThumbnailUtils
					.createVideoThumbnail(filePath, MediaStore.Images.Thumbnails.FULL_SCREEN_KIND);
			if (bitmap != null) {
				//将bitmap转为输入流返回
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				bitmap.compress(CompressFormat.PNG, 0, bos);
				return new ByteArrayInputStream(bos.toByteArray());
			}
		}
		return null;
	}

	/**
	 * 通过内容提供者的uri形式获取图片的输入流
	 * Retrieves {@link InputStream} of image by URI (image is accessed using {@link ContentResolver}).
	 *
	 * @param imageUri Image URI
	 * @param extra    Auxiliary object which was passed to {@link DisplayImageOptions.Builder#extraForDownloader(Object)
	 *                 DisplayImageOptions.extraForDownloader(Object)}; can be null
	 * @return {@link InputStream} of image
	 * @throws FileNotFoundException if the provided URI could not be opened
	 */
	protected InputStream getStreamFromContent(String imageUri, Object extra) throws FileNotFoundException {
		ContentResolver res = context.getContentResolver();

		Uri uri = Uri.parse(imageUri);
		if (isVideoContentUri(uri)) { // video thumbnail
			//video类型只获取对应缩略图
			Long origId = Long.valueOf(uri.getLastPathSegment());
			Bitmap bitmap = MediaStore.Video.Thumbnails
					.getThumbnail(res, origId, MediaStore.Images.Thumbnails.MINI_KIND, null);
			if (bitmap != null) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				bitmap.compress(CompressFormat.PNG, 0, bos);
				return new ByteArrayInputStream(bos.toByteArray());
			}
		} else if (imageUri.startsWith(CONTENT_CONTACTS_URI_PREFIX)) { // contacts photo
			//如果uri是联系人类型，返回联系人的头像
			return getContactPhotoStream(uri);
		}
		//对于一般的图片来说，直接通过ContentResolver和Uri打开输入流即可
		return res.openInputStream(uri);
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
	protected InputStream getContactPhotoStream(Uri uri) {
		ContentResolver res = context.getContentResolver();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
			return ContactsContract.Contacts.openContactPhotoInputStream(res, uri, true);
		} else {
			return ContactsContract.Contacts.openContactPhotoInputStream(res, uri);
		}
	}

	/**
	 * Retrieves {@link InputStream} of image by URI (image is located in assets of application).
	 *
	 * @param imageUri Image URI
	 * @param extra    Auxiliary object which was passed to {@link DisplayImageOptions.Builder#extraForDownloader(Object)
	 *                 DisplayImageOptions.extraForDownloader(Object)}; can be null
	 * @return {@link InputStream} of image
	 * @throws IOException if some I/O error occurs file reading
	 */
	protected InputStream getStreamFromAssets(String imageUri, Object extra) throws IOException {
		String filePath = Scheme.ASSETS.crop(imageUri);
		//通过这种形式可以直接是相对路径
		return context.getAssets().open(filePath);
	}

	/**
	 * Retrieves {@link InputStream} of image by URI (image is located in drawable resources of application).
	 *
	 * @param imageUri Image URI
	 * @param extra    Auxiliary object which was passed to {@link DisplayImageOptions.Builder#extraForDownloader(Object)
	 *                 DisplayImageOptions.extraForDownloader(Object)}; can be null
	 * @return {@link InputStream} of image
	 */
	protected InputStream getStreamFromDrawable(String imageUri, Object extra) {
		//这里要求的是drawable://123123，其中12313应该是资源id，通过R.drawable.icon获取
		String drawableIdString = Scheme.DRAWABLE.crop(imageUri);
		int drawableId = Integer.parseInt(drawableIdString);
		return context.getResources().openRawResource(drawableId);
	}

	/**
	 * Retrieves {@link InputStream} of image by URI from other source with unsupported scheme. Should be overriden by
	 * successors to implement image downloading from special sources.<br />
	 * This method is called only if image URI has unsupported scheme. Throws {@link UnsupportedOperationException} by
	 * default.
	 *
	 * @param imageUri Image URI
	 * @param extra    Auxiliary object which was passed to {@link DisplayImageOptions.Builder#extraForDownloader(Object)
	 *                 DisplayImageOptions.extraForDownloader(Object)}; can be null
	 * @return {@link InputStream} of image
	 * @throws IOException                   if some I/O error occurs
	 * @throws UnsupportedOperationException if image URI has unsupported scheme(protocol)
	 */
	protected InputStream getStreamFromOtherSource(String imageUri, Object extra) throws IOException {
		throw new UnsupportedOperationException(String.format(ERROR_UNSUPPORTED_SCHEME, imageUri));
	}

	/**
	 * 从content://类型获取当前内容是否为video类型
     */
	private boolean isVideoContentUri(Uri uri) {
		String mimeType = context.getContentResolver().getType(uri);
		return mimeType != null && mimeType.startsWith("video/");
	}

	/**
	 * 判断当前uri是不是video类型
     */
	private boolean isVideoFileUri(String uri) {
		//获得文件名后缀
		String extension = MimeTypeMap.getFileExtensionFromUrl(uri);
		//根据文件后缀获取mimeType
		String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
		return mimeType != null && mimeType.startsWith("video/");
	}
}
