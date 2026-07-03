package com.example.lending.loan.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu Method nằm ở luồng Read-Heavy.
 * Annotation này sẽ kích hoạt cơ chế AOP để chuyển hướng truy vấn CSDL
 * sang hệ thống Read-Replica (Slave) thay vì Primary DB, nhằm chia tải kết nối.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadOnlyRoute {
}
