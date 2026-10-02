package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/bwmarrin/snowflake"
	"github.com/russolsen/transit"
)

func respond(message *Message, value string) {
	buf := bytes.NewBufferString("")
	encoder := transit.NewEncoder(buf, false)

	if err := encoder.Encode(value); err != nil {
		WriteErrorResponse(message, err)
	} else {
		WriteInvokeResponse(message, buf.String())
	}
}


func generateId() (string, error) {

	// Create a new Node with a Node number of 1
	node, err := snowflake.NewNode(1)
	if err != nil {
		return "", err
	}

	// Generate a snowflake ID.
	id := node.Generate()

	return id.String(), nil
}

func processMessage(message *Message) {
	switch message.Op {
	case "describe":
		describeResponse := &DescribeResponse{
			Format: "transit+json",
			Namespaces: []Namespace{
				{
					Name: "pod.zmaillard.snowflakeid",
					Vars: []Var{
						{
							Name: "new-id",
						},
					},
				},
			},
		}
		WriteDescribeResponse(describeResponse)
	case "invoke":
		switch message.Var {
		case "pod.zmaillard.snowflakeid/new-id":
			newId, err := generateId()
			if err != nil {
				WriteErrorResponse(message, err)
				return
			}
			respond(message, newId)
		default:
			WriteErrorResponse(message, fmt.Errorf("unknown var: %s", message.Var))
		}
	default:
		WriteErrorResponse(message, fmt.Errorf("unknown var: %s", message.Var))
	}
}

func debug(v any) {
	fmt.Fprintf(os.Stderr, "debug: %+q\n", v)
}

func main() {
	for {
		message, err := ReadMessage()
		if err != nil {
			if errors.Is(err, io.EOF) {
				// Pod client closed stdin; shut down gracefully.
				return
			}
			if message != nil {
				WriteErrorResponse(message, err)
			}
			continue
		}
		processMessage(message)
	}
}
