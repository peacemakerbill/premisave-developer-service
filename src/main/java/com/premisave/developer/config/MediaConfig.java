package com.premisave.developer.config;

import com.premisave.developer.client.CloudinaryImageStorage;
import com.premisave.developer.client.ImageStorage;
import com.premisave.developer.client.JdkMediaTransport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the picture store. It is always present; it reports itself as not configured when Cloudinary is not set up. */
@Configuration("developerMediaConfig")
public class MediaConfig {

    @Bean
    ImageStorage developerImageStorage(DeveloperProperties props) {
        return new CloudinaryImageStorage(props.cloudinary(), new JdkMediaTransport(), Clock.systemUTC());
    }
}
