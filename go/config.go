package zipkin

import "fmt"

// DefaultURL is the default endpoint of Zipkin.
const DefaultURL = "http://127.0.0.1:9411"

// vendorEnv lists the vendor variables consulted after GROW_ZIPKIN_*.
// Service code adds the vendor's own names here (for example BAO_ADDR, VAULT_ADDR).
var vendorEnv = VendorEnv{}

// vendorTokenEnv lists vendor variables for the token, in precedence order.
var vendorTokenEnv []string

// Config configures a Client. Build it with a struct literal or ConfigFromEnv;
// zero values mean "use the default". Call Validate to check it.
type Config struct {
	CommonConfig

	// Token authenticates requests (env GROW_ZIPKIN_TOKEN).
	Token Secret
}

// ConfigFromEnv reads the configuration from the process environment.
func ConfigFromEnv() (Config, error) { return ConfigFromEnvWith(Config{}, nil) }

// ConfigFromEnvWith resolves explicit values > GROW_ZIPKIN_* > vendor
// variables > defaults. env replaces the process environment when non-nil.
func ConfigFromEnvWith(explicit Config, env map[string]string) (Config, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if err := c.applyEnv(r, vendorEnv); err != nil {
		return Config{}, err
	}
	if c.Token.IsZero() {
		if v, ok := r.String("TOKEN", vendorTokenEnv...); ok {
			c.Token = NewSecret(v)
		}
	}
	c.CommonConfig = c.withDefaults(DefaultURL)
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// Validate checks the configuration and returns a CONFIG error.
func (c Config) Validate() error {
	return c.withDefaults(DefaultURL).validate()
}

// String renders the configuration with every credential redacted.
func (c Config) String() string {
	return fmt.Sprintf("Config{%s Token:%s}", c.describe(), c.Token)
}

// GoString renders the configuration with every credential redacted.
func (c Config) GoString() string { return c.String() }
