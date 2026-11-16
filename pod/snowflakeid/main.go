package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/bwmarrin/snowflake"
)

func respond(message *Message, value []string) {
	resp, err := json.Marshal(value)

	if  err != nil {
		WriteErrorResponse(message, err)
	} else {
		WriteInvokeResponse(message, string(resp))
	}
}


func generateId(count int) ([]string, error) {

	// Create a new Node with a Node number of 1
	node, err := snowflake.NewNode(1)
	if err != nil {
		return []string{}, err
	}

	ids := make([]string, count)
	for i := 0; i < count; i++ {
		// Generate a snowflake ID.
		id := node.Generate()
		ids[i] = id.String()
	}

	return ids, nil
}

func processMessage(message *Message) {
	switch message.Op {
	case "describe":
		describeResponse := &DescribeResponse{
			Format: "json",
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
			reqCount := []int{}
			err := json.Unmarshal([]byte(message.Args), &reqCount)
			if err != nil {
				WriteErrorResponse(message, err)
				return
			}
			newId, err := generateId(reqCount[0])
			debug(newId)
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
