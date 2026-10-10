package com.fitfam.api.web;

import org.springframework.http.HttpStatus;

/** An error that is reported to the client as {@code {"error": "<code>"}} with the given HTTP status. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final String detail;

	public ApiException(HttpStatus status, String code) {
		this(status, code, null);
	}

	/** {@code detail} is a short technical hint (for example the path of an invalid field), never user data. */
	public ApiException(HttpStatus status, String code, String detail) {
		super(code);
		this.status = status;
		this.code = code;
		this.detail = detail;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public String getDetail() {
		return detail;
	}

}
