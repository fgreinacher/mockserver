module github.com/mock-server/mockserver-monorepo/examples/go/callback

go 1.21

require github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7 v7.0.0

require github.com/gorilla/websocket v1.5.3 // indirect

replace github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7 => ../../../mockserver-client-go
