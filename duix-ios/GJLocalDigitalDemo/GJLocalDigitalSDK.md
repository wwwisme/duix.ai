## 硅基本地版DUIX SDK使⽤⽂档 (1.0.3)

 
 ### 1. 支持的系统和硬件版本

| 项目     | 描述                                                               |
|--------|------------------------------------------------------------------|
| 系统     | ios12及以上 |
| 硬件要求   | 要求设备 CPU4 核极以上,内存 4G 及以上。可用存储空间 500MB 及以上。                       |
| 网络     | 支持 WIF 及移动网络。如果使用云端问答库,设备带宽(用于数字人的实际带宽)期望 10mbps 及以上。            |
| 内存要求   | 可用于数字人的内存 >= 400MB                                               |

      
```
### 开发环境
开发⼯具: Xcode  ios12.0以上 iphoneX及以上

## 快速开始
```
  
              NSString *basePath =[NSString stringWithFormat:@"%@/%@",[[NSBundle mainBundle] bundlePath],@"gj_dh_res"];
              NSString *digitalPath =[NSString stringWithFormat:@"%@/%@",[[NSBundle mainBundle] bundlePath],@"lixin_a_540s"];
          
            //初始化
           NSInteger result=   [[GJLDigitalManager manager] initBaseModel:basePath digitalModel:digitalPath showView:weakSelf.showView];
            if(result==1)
            {
               //开始
                [[GJLDigitalManager manager] toStart:^(BOOL isSuccess, NSString *errorMsg) {
                    if(!isSuccess)
                    {
                        [SVProgressHUD showInfoWithStatus:errorMsg];
                    }
                }];
            }
       
```
## 调用流程
```
1. 启动服务前需要准备好同步数字人需要的基础配置和模型文件。
2. 初始化数字人渲染服务。
3. 调用toStart函数开始渲染数字人
4. 调用toSpeakWithPath函数驱动数字人播报。
5. 调用cancelAudioPlay函数可以主动停止播报。
6. 调用toStop结束并释放数字人渲染
```

### SDK回调

```
/*
*数字人渲染报错回调
* -1未初始化 50009资源超时或未配置
*/
@property (nonatomic, copy) void (^playFailed)(NSInteger code,NSString *errorMsg);

/*
*音频播放结束回调
*/
@property (nonatomic, copy) void (^audioPlayEnd)(void);

/*
*音频播放进度回调
/
@property (nonatomic, copy) void (^audioPlayProgress)(float current,float total);
```

## 方法


```

### 初始化

```
/*
*basePath 底层通用模型路径-保持不变
*digitalPath 数字人模型路径- 替换数字人只需要替换这个路径
*return 1 返回成功  -1 初始化失败
*showView 显示界面
*/
-(NSInteger)initBaseModel:(NSString*)basePath digitalModel:(NSString*)digitalPath showView:(UIView*)showView;
```

### 替换背景

```
/*
* bbgPath 替换背景 
* 注意: -jpg格式 ----背景size等于数字人模型的getDigitalSize-----------
*/
-(void)toChangeBBGWithPath:(NSString*)bbgPath;
```

### 播放音频

```
/*
*wavPath 音频的本地路径
*1通道 16位深 16000采样率的wav本地文件
*/
-(void)toSpeakWithPath:(NSString*)wavPath;
```

### 开始渲染数字人

```
/*
*开始
*/
-(void)toStart:(void (^) (BOOL isSuccess, NSString *errorMsg))block;
```

### 结束渲染数字人并释放
```
/*
*结束
*/
-(void)toStop;
```

### 数字人模型的宽度高度

```
/*
*初始化模型过后才能获取
*getDigitalSize 数字人模型的宽度 数字人模型的高度
*/
-(CGSize)getDigitalSize;
```

### 取消播放音频

```
/*
*取消播放音频
*/
-(void)cancelAudioPlay;
```




## 动作

### 随机动作
 
```
/*
* 开始动作前调用
* 随机动作（一段文字包含多个音频，建议第一个音频开始时设置随机动作）
* return 0 数字人模型不支持随机动作 1 数字人模型支持随机动作
*/
-(NSInteger)toRandomMotion;
```

### 开始动作

```
/*
* 开始动作 （一段文字包含多个音频，第一个音频开始时设置）
* return 0  数字人模型不支持开始动作 1  数字人模型支持开始动作
*/
-(NSInteger)toStartMotion;
```

### 结束动作
```
/*
* 结束动作 （一段文字包含多个音频，最后一个音频播放结束时设置）
*isQuickly YES 立即结束动作   NO 等待动作播放完成再静默
*return 0 数字人模型不支持结束动作  1 数字人模型支持结束动作
*/
-(NSInteger)toSopMotion:(BOOL)isQuickly;
```

### 暂停后开始播放数字人
```
/*
*暂停后才需执行播放数字人
*/
-(void)toPlay;
```

### 暂停数字人播放
```
/*
*暂停数字人播放
*/
-(void)toPause;
```


## 其他相关的第三方开源项目

| 模块        | 描述 |
|-----------|--|
| [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) | 图片处理框架 |
| [onnx](https://github.com/onnx/onnx) | 人工智能框架 |
| [ncnn](https://github.com/Tencent/ncnn) | 高性能神经网络计算框架 |
