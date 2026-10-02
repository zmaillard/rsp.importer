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

#-- rclone  staging and AI
#-- rename files to use snowflake id
#-- batch resize webp, jpeg, and avif - build new folders 
#https://havecamerawilltravel.com/workflow/imagemagick-convert-avif-webp/
#-- extract exif metadata and save next to image
#-- rclone sync - only jpeg, avif, webp files to cloud storage
#-- update database with new file paths and metadata
#--Query files
