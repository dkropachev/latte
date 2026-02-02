module github.com/scylladb/latte/cql-adapters/gocql

go 1.25.0

require (
	github.com/gocql/gocql v1.7.0
	github.com/rs/zerolog v1.33.0
	gopkg.in/inf.v0 v0.9.1
)

require (
	github.com/google/uuid v1.6.0 // indirect
	github.com/klauspost/compress v1.18.3 // indirect
	github.com/mattn/go-colorable v0.1.13 // indirect
	github.com/mattn/go-isatty v0.0.20 // indirect
	golang.org/x/sys v0.25.0 // indirect
)

replace github.com/gocql/gocql => github.com/scylladb/gocql v1.17.1
