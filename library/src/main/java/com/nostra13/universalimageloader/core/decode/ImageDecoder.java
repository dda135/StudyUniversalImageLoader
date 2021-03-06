/*******************************************************************************
 * Copyright 2013 Sergey Tarasevich
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
package com.nostra13.universalimageloader.core.decode;

import android.graphics.Bitmap;

import java.io.IOException;

/**
 * Provide decoding image to result {@link Bitmap}.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @see ImageDecodingInfo
 * @since 1.8.3
 */
public interface ImageDecoder {

	/**
	 * 根据指定的目标大小和参数处理图片
	 * @param imageDecodingInfo 处理的时候可能需要使用的参数
	 * @return 处理后的bitmap
	 */
	Bitmap decode(ImageDecodingInfo imageDecodingInfo) throws IOException;
}
