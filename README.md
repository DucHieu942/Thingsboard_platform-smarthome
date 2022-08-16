# ThingsBoard 


<img src="./img/logo.png?raw=true" width="100" height="100">

## Documentation

ThingsBoard documentation is hosted on [thingsboard.io](https://thingsboard.io/docs).

## Thingsboard Release 3.1

## Prerequisites
- OS: Ubuntu 18.04 LTS
- Java (OpenJDK 11)

## Run project
- Change to project's directory
- Installing project with command line:
```
  sudo mvn clean install -DskipTests
```
- Run file shell script to start/stop project
```
    ./thingsboard.sh start
```
```
    ./thingsboard.sh stop
```
- Or run with command line:
```
   java -jar application/target/thingsboard-3.3.4-boot.jar
```

## Result
- API: create several APIs to take device's information and send data (temperature) to server in Transport module.
  API get information of device with parameter is device's id
    ![Screenshot from 2021-06-26 11-14-23](https://user-images.githubusercontent.com/49309912/123501544-e795cc00-d66f-11eb-8321-f83bd0681c2a.png)
    </br>
    </br>
  API send temperature to server which is the lastest telemetry with parameter is value temprature
  ![Screenshot from 2021-06-26 11-54-15](https://user-images.githubusercontent.com/49309912/123502264-44e04c00-d675-11eb-8cdc-57895b4b49c9.png)
  </br>


- UI: customize UI page with SET's logo
  </br>
  Login Page
  ![Screenshot from 2021-06-20 22-17-10](https://user-images.githubusercontent.com/49309912/123501234-9dabe680-d66d-11eb-8683-f99895364aa2.png)
  </br>
  </br>
  Home Page
  ![Screenshot from 2021-06-20 22-16-58](https://user-images.githubusercontent.com/49309912/123501236-9f75aa00-d66d-11eb-830a-05cf09a50602.png)



## Licenses

This project is released under [Apache 2.0 License](./LICENSE).
