build:
	sam build

deploy: build
	sam deploy --guided

deploy-no-confirm: build
	sam deploy --no-confirm-changeset

logs:
	sam logs -n SlackEventFunction --stack-name slack-ai-assistant --tail

test:
	./gradlew test

clean:
	./gradlew clean
	rm -rf .aws-sam

.PHONY: build deploy deploy-no-confirm logs test clean