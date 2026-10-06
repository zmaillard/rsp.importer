FROM golang:1.26-trixie as builder
WORKDIR /app
COPY ./pod/snowflakeid/go.sum  ./pod/snowflakeid/go.mod ./
RUN go mod download
COPY ./pod/snowflakeid/*.go  ./
RUN CGO_ENABLED=0 GOOS=linux go build -o /snowflakeid


FROM babashka/babashka:1.13.222

RUN apt-get update && apt-get install -y imagemagick rclone exiftool jq

COPY . .
COPY --from=builder /snowflakeid /pod/snowflakeid/snowflakeid

