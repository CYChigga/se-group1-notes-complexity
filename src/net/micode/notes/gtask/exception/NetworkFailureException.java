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
 * 同步链路的“网络不可用”：登录失败、连接或读写超时、HTTP 响应无法解析成 JSON。
 * 与 ActionFailureException 相对——它属于可重试故障，因此设计为受检异常，
 * 强制调用方在方法签名上声明，并在边界处转成 STATE_NETWORK_ERROR 提示用户稍后再试。
 * 保留带 cause 的构造器，便于把底层 IOException 原样带进日志。
 */
public class NetworkFailureException extends Exception {
    private static final long serialVersionUID = 2107610287180234136L;

    public NetworkFailureException() {
        super();
    }

    public NetworkFailureException(String paramString) {
        super(paramString);
    }

    public NetworkFailureException(String paramString, Throwable paramThrowable) {
        super(paramString, paramThrowable);
    }
}
