// Package zipkin is the Grow Codes SDK for Zipkin.
//
// Create a client from the environment (GROW_ZIPKIN_URL,
// GROW_ZIPKIN_TOKEN, ...) or from a Config literal:
//
//	client, err := zipkin.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, zipkin.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Zipkin is a trademark of The OpenZipkin authors. This package is not affiliated
// with or endorsed by The OpenZipkin authors.
package zipkin
