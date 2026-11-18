module github.com/grow-codes-packages/zipkin/go/integration

go 1.26

require github.com/grow-codes-packages/zipkin/go v0.0.0

// The consumer module uses the package from this repository, exactly as a
// user would import it; docker/Dockerfile.go builds it this way.
replace github.com/grow-codes-packages/zipkin/go => ../
