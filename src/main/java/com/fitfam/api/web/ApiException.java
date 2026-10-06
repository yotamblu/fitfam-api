package com.fitfam.api.web;

import org.springframework.http.HttpStatus;

/** An error that is reported to the client as {@code {"error": "<code>"}} with the given HTTP status. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String code) {
		super(code);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

}
