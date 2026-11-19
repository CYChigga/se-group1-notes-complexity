/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.gtask.exception;

/**
 * 同步链路的“内部状态错误”：请求本身合法，但本地数据或协议状态不允许完成。
 * 典型场景：便签找不到父目录映射、远端 JSON 结构不符合预期、更新时 id 非法。
 * 与 NetworkFailureException 相对——它不可通过重试恢复，故继承 RuntimeException，
 * 免去每层方法签名声明受检异常；最终由 GTaskManager.sync 统一映射为 STATE_INTERNAL_ERROR。
 */
public class ActionFailureException extends RuntimeException {
    private static final long serialVersionUID = 4425249765923293627L;

    public ActionFailureException() {
        super();
    }

    public ActionFailureException(String paramString) {
        super(paramString);
    }

    public ActionFailureException(String paramString, Throwable paramThrowable) {
        super(paramString, paramThrowable);
    }
}
