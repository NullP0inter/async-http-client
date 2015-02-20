/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.future;

import java.io.IOException;

public class StackTraceInspector {

    private static boolean exceptionInMethod(Throwable t, String className, String methodName) {
        try {
            for (StackTraceElement element : t.getStackTrace()) {
                if (element.getClassName().equals(className) && element.getMethodName().equals(methodName))
                    return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean recoverOnConnectCloseException(Throwable t) {
        return exceptionInMethod(t, "sun.nio.ch.SocketChannelImpl", "checkConnect")
                || (t.getCause() != null && recoverOnConnectCloseException(t.getCause()));
    }

    public static boolean recoverOnDisconnectException(Throwable t) {
        return exceptionInMethod(t, "org.jboss.netty.handler.ssl.SslHandler", "channelDisconnected")
                || (t.getCause() != null && recoverOnConnectCloseException(t.getCause()));
    }

    public static boolean recoverOnReadOrWriteException(Throwable t) {

        if (t instanceof IOException && "Connection reset by peer".equalsIgnoreCase(t.getMessage()))
            return true;

        try {
            for (StackTraceElement element : t.getStackTrace()) {
                String className = element.getClassName();
                String methodName = element.getMethodName();
                if (className.equals("sun.nio.ch.SocketDispatcher") && (methodName.equals("read") || methodName.equals("write")))
                    return true;
            }
        } catch (Throwable ignore) {
        }

        if (t.getCause() != null)
            return recoverOnReadOrWriteException(t.getCause());

        return false;
    }
}