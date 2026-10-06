package main

import (
	"bufio"
	"os"

	"github.com/jackpal/bencode-go"
)

type Message struct {
	Op   string
	Id   string
	Args string
	Var  string
}

type Namespace struct {
	Name string `bencode:"name"`
	Vars []Var  `bencode:"vars"`
}

type Var struct {
	Name string `bencode:"name"`
	Code string `bencode:"code,omitempty"`
}

type DescribeResponse struct {
	Format     string      `bencode:"format"`
	Namespaces []Namespace `bencode:"namespaces"`
}

type InvokeResponse struct {
	Id     string   `bencode:"id"`
	Value  string   `bencode:"value"` // stringified json response
	Status []string `bencode:"status"`
}

type ErrorResponse struct {
	Id        string   `bencode:"id"`
	Status    []string `bencode:"status"`
	ExMessage string   `bencode:"ex-message"`
	ExData    string   `bencode:"ex-data"`
}

func ReadMessage() (*Message, error) {
	reader := bufio.NewReader(os.Stdin)
	message := &Message{}
	if err := bencode.Unmarshal(reader, &message); err != nil {
		return nil, err
	}

	return message, nil
}

func WriteDescribeResponse(describeResponse *DescribeResponse) {
	writeResponse(*describeResponse)
}

// WriteInvokeResponse writes an invoke response whose value is already
// encoded in the payload format declared by the pod's describe response
// (e.g. transit+json). value must be a string containing that encoded
// payload; it is written as-is, not re-encoded.
func WriteInvokeResponse(inputMessage *Message, value string) error {
	if value == "" {
		return nil
	}
	response := InvokeResponse{Id: inputMessage.Id, Status: []string{"done"}, Value: value}
	writeResponse(response)

	return nil
}

// WriteNotDoneInvokeResponse is like WriteInvokeResponse but omits the
// "done" status, allowing the pod to send additional values afterwards.
func WriteNotDoneInvokeResponse(inputMessage *Message, value string) error {
	if value == "" {
		return nil
	}
	response := InvokeResponse{Id: inputMessage.Id, Status: []string{}, Value: value}
	writeResponse(response)

	return nil
}

func WriteErrorResponse(inputMessage *Message, err error) {
	errorResponse := ErrorResponse{Id: inputMessage.Id, Status: []string{"done", "error"}, ExMessage: err.Error()}
	writeResponse(errorResponse)
}

func writeResponse(response any) error {
	writer := bufio.NewWriter(os.Stdout)
	if err := bencode.Marshal(writer, response); err != nil {
		return err
	}

	writer.Flush()

	return nil
}
