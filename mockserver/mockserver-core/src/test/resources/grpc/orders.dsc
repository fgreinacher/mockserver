
¬
orders_common.protocom.example.orders.common"B
Money#
currency_code (	RcurrencyCode
units (RunitsB.
com.example.orders.commonBOrderCommonProtosbproto3
ÿ
google/protobuf/timestamp.protogoogle.protobuf";
	Timestamp
seconds (Rseconds
nanos (RnanosB…
com.google.protobufBTimestampProtoPZ2google.golang.org/protobuf/types/known/timestamppbø¢GPBªGoogle.Protobuf.WellKnownTypesbproto3
û
google/protobuf/duration.protogoogle.protobuf":
Duration
seconds (Rseconds
nanos (RnanosBƒ
com.google.protobufBDurationProtoPZ1google.golang.org/protobuf/types/known/durationpbø¢GPBªGoogle.Protobuf.WellKnownTypesbproto3
†
google/protobuf/wrappers.protogoogle.protobuf"#
DoubleValue
value (Rvalue""

FloatValue
value (Rvalue""

Int64Value
value (Rvalue"#
UInt64Value
value (Rvalue""

Int32Value
value (Rvalue"#
UInt32Value
value (Rvalue"!
	BoolValue
value (Rvalue"#
StringValue
value (	Rvalue""

BytesValue
value (RvalueBƒ
com.google.protobufBWrappersProtoPZ1google.golang.org/protobuf/types/known/wrapperspbø¢GPBªGoogle.Protobuf.WellKnownTypesbproto3
Ì
orders.protocom.example.ordersorders_common.protogoogle/protobuf/timestamp.protogoogle/protobuf/duration.protogoogle/protobuf/wrappers.proto",
GetOrderRequest
order_id (	RorderId"ü
Order
order_id (	RorderId6
total (2 .com.example.orders.common.MoneyRtotal7
	placed_at (2.google.protobuf.TimestampRplacedAtB
processing_time (2.google.protobuf.DurationRprocessingTime0
note (2.google.protobuf.StringValueRnote8
	expedited (2.google.protobuf.BoolValueR	expedited7
quantity (2.google.protobuf.Int32ValueRquantity2Z
OrderServiceJ
GetOrder#.com.example.orders.GetOrderRequest.com.example.orders.OrderB!
com.example.ordersBOrderProtosbproto3